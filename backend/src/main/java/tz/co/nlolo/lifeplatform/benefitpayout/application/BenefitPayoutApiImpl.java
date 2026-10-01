package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.api.*;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.*;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.*;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;
import tz.co.nlolo.lifeplatform.product.api.PayoutPlan;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The payout engine. Tasks 3 to 7 add the acting half; this is what makes a policy's schedule
 * exist and keeps it in step with the premiums billing reports.
 */
@Service
public class BenefitPayoutApiImpl implements BenefitPayoutApi {

    /** Passed as the "after" bound to cancel EVERY instalment. Not LocalDate.MIN -- Postgres
     *  cannot bind it, and the failure would surface deep inside a listener. */
    static final LocalDate BEGINNING = LocalDate.of(1900, 1, 1);

    /** Statuses in which a policy can still be owed a payout. PAID_UP keeps reduced cover and so
     *  keeps its benefits; SUSPENDED is owed them but holds until it resumes. */
    private static final Set<String> PAYABLE = Set.of("ACTIVE", "REINSTATED", "PAID_UP", "SUSPENDED");

    /** Written on the rows a lapse withdrew, and matched exactly when reinstatement brings them
     *  back -- so a row cancelled for any OTHER reason is never silently revived. */
    static final String LAPSE_REASON = "Policy lapsed";

    private final PayoutInstalmentRepository instalments;
    private final PayoutStreamRepository streams;
    private final PremiumTallyRepository tallies;
    private final PaymentRunRepository runs;
    private final FreeLookCancellationRepository cancellations;
    private final FreeLookDeductionRepository deductions;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher eventPublisher;

    public BenefitPayoutApiImpl(PayoutInstalmentRepository instalments, PayoutStreamRepository streams,
                                PremiumTallyRepository tallies, PaymentRunRepository runs,
                                FreeLookCancellationRepository cancellations, FreeLookDeductionRepository deductions,
                                ProductApi productApi, PolicyApi policyApi,
                                ApplicationEventPublisher eventPublisher) {
        this.cancellations = cancellations;
        this.deductions = deductions;
        this.instalments = instalments;
        this.streams = streams;
        this.tallies = tallies;
        this.runs = runs;
        this.productApi = productApi;
        this.policyApi = policyApi;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayoutInstalmentView> listForPolicy(String policyNumber) {
        return instalments.findByPolicyNumberOrderByDueDateAscRowOrderAsc(policyNumber).stream().map(Views::of).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PayoutInstalmentView getInstalment(UUID instalmentId) {
        return Views.of(load(instalmentId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasScheduledMaturity(String policyNumber) {
        return instalments.existsByPolicyNumberAndKindIn(policyNumber,
            List.of(PayoutKind.MATURITY.name(), PayoutKind.RETURN_OF_PREMIUM.name()));
    }

    /**
     * {@code policy.PolicyIssued}: open the premium tally and store every instalment the version's
     * plan implies.
     *
     * <p>Idempotent on the tally's presence, because an event may be redelivered and a doubled
     * schedule would pay a customer twice. The unique index on (policy, row, date) is the second
     * line of that defence.
     */
    @Transactional
    public void expandForIssuedPolicy(String policyNumber, UUID productVersionId, LocalDate issueDate,
                                      LocalDate premiumPayingUntil, String premiumFrequency) {
        UUID tenantId = TenantContext.get();
        if (tallies.existsById(policyNumber)) {
            return;
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        tallies.save(new PremiumTally(tenantId, policyNumber, premiumFrequency, issueDate, premiumPayingUntil,
            policy.sumAssuredCurrency()));

        PayoutPlan plan = productApi.resolvePayoutPlan(productVersionId);
        if (plan.rows().isEmpty()) {
            return;
        }
        // Risk commences, not the issue date: a policy issued today may carry risk from next month,
        // and a policy year is counted from when cover actually started.
        LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : issueDate;

        Map<Integer, UUID> streamByRow = new HashMap<>();
        for (int order = 0; order < plan.rows().size(); order++) {
            if (plan.rows().get(order).kind() == PayoutKind.INCOME) {
                PayoutStream stream = streams.save(new PayoutStream(tenantId, policyNumber, order,
                    plan.terms().proofOfLifeIntervalMonths()));
                streamByRow.put(order, stream.getStreamId());
            }
        }
        for (ScheduleExpander.Planned p : ScheduleExpander.expand(plan, start, policy.maturityDate(), policy.sumAssuredAmount())) {
            instalments.save(new PayoutInstalment(tenantId, policyNumber, p.kind(), p.rowOrder(),
                streamByRow.get(p.rowOrder()), p.dueDate(), p.amount(), policy.sumAssuredCurrency()));
        }
    }

    /**
     * {@code billing.PremiumCollected}: add to the tally, and release anything the payment cured.
     *
     * <p>A policy with no tally is one issued before this module existed, or a group scheme --
     * neither has a schedule, so there is nothing to do and nothing to log.
     */
    @Transactional
    public void recordPremium(String policyNumber, BigDecimal amount, LocalDate paidToDate) {
        tallies.findById(policyNumber).ifPresent(tally -> {
            tally.record(amount, paidToDate);
            tallies.save(tally);
            for (PayoutInstalment held : instalments.findByPolicyNumberAndStatus(policyNumber, InstalmentStatus.ON_HOLD.name())) {
                if (tally.isPaidUpTo(held.getDueDate()) && streamAllowsRelease(held)) {
                    held.release();
                    instalments.save(held);
                }
            }
        });
    }

    /**
     * The drain's per-row work: SCHEDULED -> DUE, ON_HOLD or CANCELLED, and for an end-of-term
     * payout, maturing the policy.
     *
     * <p>Guarded on SCHEDULED, so a second drain instance racing the first does nothing. That plus
     * the aggregate's {@code @Version} is what makes running this in two places safe.
     */
    @Transactional
    public void fallDue(UUID instalmentId) {
        PayoutInstalment i = load(instalmentId);
        if (i.status() != InstalmentStatus.SCHEDULED) {
            return;
        }
        PolicyView policy = policyApi.getPolicy(i.getPolicyNumber());
        String policyStatus = policy.status().name();
        if (!PAYABLE.contains(policyStatus)) {
            // Lapsed, surrendered, cancelled: nothing is owed and nothing will be.
            i.cancel("Policy is " + policyStatus + " on the due date");
            instalments.save(i);
            return;
        }

        PremiumTally tally = tallies.findById(i.getPolicyNumber()).orElse(null);
        BigDecimal valued = null;
        if (i.kind() == PayoutKind.RETURN_OF_PREMIUM) {
            valued = premiumReturnAmount(policy, i, tally);
            if (valued.signum() == 0) {
                // Nothing was ever collected, so there is nothing to return. The policy still
                // matures: its term ran out either way.
                i.cancel("No premiums were collected, so there is nothing to return");
                instalments.save(i);
                matureIfEndOfTerm(i);
                return;
            }
        }

        boolean upToDate = tally == null || tally.isPaidUpTo(i.getDueDate());
        i.fallDue(upToDate, valued);
        if (i.status() == InstalmentStatus.DUE && "SUSPENDED".equals(policyStatus)) {
            i.hold("Policy is suspended");
        } else if (i.status() == InstalmentStatus.DUE && !streamAllowsRelease(i)) {
            i.hold("Proof of life is overdue");
        }
        instalments.save(i);
        matureIfEndOfTerm(i);
    }

    /** The authored percentage applied to what billing actually collected (decision Q5). */
    private BigDecimal premiumReturnAmount(PolicyView policy, PayoutInstalment i, PremiumTally tally) {
        BigDecimal percent = productApi.resolvePayoutPlan(policy.productVersionId()).rows()
            .get(i.getRowOrder()).amountValue();
        BigDecimal collected = tally != null ? tally.getPremiumsCollected() : BigDecimal.ZERO;
        return PayoutArithmetic.percentOf(collected, percent);
    }

    /**
     * A maturity or premium return falling due ends the contract, whether or not the money has
     * gone out yet -- cover stops on the date, exactly as step 1's surrender stops it at approval.
     */
    private void matureIfEndOfTerm(PayoutInstalment i) {
        if (i.kind() == PayoutKind.MATURITY || i.kind() == PayoutKind.RETURN_OF_PREMIUM) {
            policyApi.markMatured(i.getPolicyNumber(), "system:benefitpayout");
        }
    }

    @Override
    @Transactional
    public PayoutInstalmentView review(UUID instalmentId, String payeeRef, ProofOfLifeMethod method,
                                       UUID documentId, String reviewer) {
        PayoutInstalment i = load(instalmentId);
        i.review(reviewer, payeeRef, method, documentId);
        return Views.of(instalments.save(i));
    }

    @Override
    @Transactional
    public PayoutInstalmentView approve(UUID instalmentId, String approver) {
        PayoutInstalment i = load(instalmentId);
        i.approve(approver);
        instalments.save(i);
        // The first approved instalment of an income stream starts its proof-of-life clock.
        if (i.getStreamId() != null) {
            streams.findById(i.getStreamId())
                .filter(s -> s.status() == StreamStatus.PENDING_ACTIVATION)
                .ifPresent(s -> {
                    s.activate(LocalDate.now());
                    streams.save(s);
                });
        }
        publishPayoutRequested(i);
        return Views.of(i);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PayoutInstalmentView> search(Collection<InstalmentStatus> statuses, Pageable pageable) {
        Collection<InstalmentStatus> wanted = statuses == null || statuses.isEmpty()
            ? EnumSet.allOf(InstalmentStatus.class) : statuses;
        return instalments.findByStatusIn(wanted.stream().map(Enum::name).toList(), pageable).map(Views::of);
    }

    /** The disbursement landed. Publishes {@code PayoutPaid}, which finaccounting books. */
    @Transactional
    public void markPaid(UUID instalmentId, UUID disbursementId) {
        PayoutInstalment i = load(instalmentId);
        if (i.status() != InstalmentStatus.APPROVED) {
            return; // a redelivered completion, already closed out
        }
        i.markPaid(disbursementId);
        instalments.save(i);
        // On the real APPROVED -> PAID transition only, so a redelivery cannot post the same
        // payout to the ledger twice.
        eventPublisher.publishEvent(DomainEventEnvelope.of("benefitpayout.PayoutPaid", TenantContext.get(),
            Map.of("instalmentId", instalmentId.toString(),
                   "policyNumber", i.getPolicyNumber(),
                   "kind", i.kind().name(),
                   "paidAmount", Map.of("amount", i.getCurrentAmount().toPlainString(),
                        "currencyCode", i.getCurrency()))));
    }

    /** The disbursement failed outright -- the money did not move, so it can be tried again. */
    @Transactional
    public void markFailed(UUID instalmentId) {
        PayoutInstalment i = load(instalmentId);
        if (i.status() == InstalmentStatus.APPROVED) {
            i.markFailed();
            instalments.save(i);
        }
    }

    @Override
    @Transactional
    public PayoutInstalmentView retry(UUID instalmentId) {
        PayoutInstalment i = load(instalmentId);
        i.retry();
        instalments.save(i);
        publishPayoutRequested(i);
        return Views.of(i);
    }

    /** Ask payment to disburse. The key carries the attempt, so a retry is a NEW request rather
     *  than a duplicate payment dedupes away. */
    void publishPayoutRequested(PayoutInstalment i) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("benefitpayout.PayoutRequested", TenantContext.get(),
            Map.of("instalmentId", i.getInstalmentId().toString(),
                   "idempotencyKey", i.getInstalmentId() + ":" + i.getAttempts(),
                   "policyNumber", i.getPolicyNumber(),
                   "payeeRef", i.getPayeeRef(),
                   "purpose", purposeFor(i.kind()),
                   "amount", Map.of("amount", i.getCurrentAmount().toPlainString(),
                        "currencyCode", i.getCurrency()))));
    }

    static String purposeFor(PayoutKind kind) {
        return switch (kind) {
            case MATURITY -> "MATURITY_PAYOUT";
            case SURVIVAL -> "SURVIVAL_BENEFIT_PAYOUT";
            case INCOME -> "INCOME_PAYOUT";
            case RETURN_OF_PREMIUM -> "PREMIUM_RETURN_PAYOUT";
        };
    }

    /**
     * Free-look is a right of an individual buyer (guide §21.3). A group or credit-life scheme is
     * cancelled under the terms the employer or lender negotiated, not under this window.
     */
    private static final Set<String> INDIVIDUAL_CATEGORIES =
        Set.of("TERM_LIFE", "ENDOWMENT", "WHOLE_LIFE", "EDUCATION_SAVINGS");

    /** The statuses {@code ux_free_look_live} treats as in flight. */
    private static final Set<String> LIVE_CANCELLATION = Set.of("REQUESTED", "APPROVED");

    @Override
    @Transactional
    public FreeLookCancellationView requestFreeLook(String policyNumber, String payeeRef,
                                                    List<FreeLookDeductionInput> deductionInputs,
                                                    String requestedBy) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        if (!INDIVIDUAL_CATEGORIES.contains(policy.productCategory())) {
            throw new PayoutStateException("Free-look applies to individual policies; a "
                + policy.productCategory() + " scheme is cancelled under its contract");
        }
        if (policy.status() != PolicyStatus.ACTIVE) {
            throw new PayoutStateException("Policy " + policyNumber + " is " + policy.status()
                + "; only an ACTIVE policy can be cancelled in free-look");
        }
        // At most one live cancellation per policy. ux_free_look_live enforces it, but a unique
        // index raises DataIntegrityViolationException, which nothing maps -- so without this the
        // second click of a slow button is a 500 rather than an answer. The same pre-check
        // requestSurrender makes, for the same reason and in the same shape.
        cancellations.findFirstByPolicyNumberOrderByRequestedAtDesc(policyNumber)
            .filter(live -> LIVE_CANCELLATION.contains(live.getStatus()))
            .ifPresent(live -> {
                throw new PayoutStateException("A free-look cancellation is already "
                    + live.getStatus().toLowerCase() + " on policy " + policyNumber);
            });

        Integer days = productApi.resolvePayoutPlan(policy.productVersionId()).terms().freeLookDays();
        if (days == null) {
            throw new PayoutStateException("Policy " + policyNumber + "'s product version has no free-look period");
        }
        // Counted from ISSUE, not from commencement: the window runs from when the customer
        // received the contract and could read it.
        LocalDate lastDay = policy.issueDate().plusDays(days);
        if (LocalDate.now().isAfter(lastDay)) {
            throw new PayoutStateException("Policy " + policyNumber + "'s free-look period ended on " + lastDay);
        }

        List<FreeLookDeductionInput> items = deductionInputs != null ? deductionInputs : List.of();
        for (FreeLookDeductionInput d : items) {
            if (d.description() == null || d.description().isBlank() || d.amount() == null || d.amount().signum() <= 0) {
                throw new PayoutStateException("Every deduction needs a description and an amount greater than zero");
            }
        }
        BigDecimal total = items.stream().map(FreeLookDeductionInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal collected = tallies.findById(policyNumber)
            .map(PremiumTally::getPremiumsCollected).orElse(BigDecimal.ZERO);

        UUID tenantId = TenantContext.get();
        FreeLookCancellation c = cancellations.save(FreeLookCancellation.request(tenantId, policyNumber, collected,
            total, policy.premiumCurrency(), payeeRef, requestedBy));
        for (FreeLookDeductionInput d : items) {
            deductions.save(new FreeLookDeduction(tenantId, c.getCancellationId(), d.description(), d.amount(),
                d.documentId()));
        }
        return freeLookView(c);
    }

    @Override
    @Transactional
    public FreeLookCancellationView approveFreeLook(UUID cancellationId, String approver) {
        FreeLookCancellation c = cancellations.findById(cancellationId)
            .orElseThrow(() -> new PayoutNotFoundException(cancellationId));
        c.approve(approver);
        cancellations.save(c);
        // The policy goes first: everything downstream -- billing stopping, commission going back --
        // hangs off PolicyCancelledFreeLook, and a schedule withdrawn against a policy that then
        // failed to cancel would be the worst of both.
        policyApi.cancelForFreeLook(c.getPolicyNumber(), approver);
        cancelFuture(c.getPolicyNumber(), BEGINNING, "Cancelled in free-look");
        // A refund of nothing is not sent to the rail. It happens whenever the deductions used up
        // the premiums exactly, and a zero disbursement would be a payment nobody can reconcile.
        if (c.getRefundAmount().signum() > 0) {
            eventPublisher.publishEvent(DomainEventEnvelope.of("benefitpayout.PayoutRequested", TenantContext.get(),
                Map.of("cancellationId", cancellationId.toString(),
                       "idempotencyKey", "free-look:" + cancellationId,
                       "policyNumber", c.getPolicyNumber(),
                       "payeeRef", c.getPayeeRef(),
                       "purpose", "FREE_LOOK_REFUND",
                       "amount", Map.of("amount", c.getRefundAmount().toPlainString(),
                            "currencyCode", c.getCurrency()))));
        }
        return freeLookView(c);
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.Optional<FreeLookCancellationView> findFreeLook(String policyNumber) {
        return cancellations.findFirstByPolicyNumberOrderByRequestedAtDesc(policyNumber).map(this::freeLookView);
    }

    /** The refund landed. Idempotent on APPROVED, like every other outcome handler here. */
    @Transactional
    public void markFreeLookRefunded(UUID cancellationId, UUID disbursementId) {
        cancellations.findById(cancellationId).ifPresent(c -> {
            c.markPaid(disbursementId);
            cancellations.save(c);
        });
    }

    @Transactional
    public void markFreeLookRefundFailed(UUID cancellationId) {
        cancellations.findById(cancellationId).ifPresent(c -> {
            c.markFailed();
            cancellations.save(c);
        });
    }

    private FreeLookCancellationView freeLookView(FreeLookCancellation c) {
        return new FreeLookCancellationView(c.getCancellationId(), c.getPolicyNumber(), c.getStatus(),
            c.getPremiumsCollected(), c.getRefundAmount(), c.getCurrency(), c.getPayeeRef(), c.getRequestedBy(),
            c.getApprovedBy(),
            deductions.findByCancellationId(c.getCancellationId()).stream()
                .map(d -> new FreeLookDeductionInput(d.getDescription(), d.getAmount(), d.getDocumentId())).toList());
    }

    /**
     * Gather today's ready stream instalments into this tenant's run for the date.
     *
     * <p>Idempotent per date: the run is found or created by its date, and a run that has already
     * been approved is left alone -- what it did not carry is picked up by tomorrow's. That is why
     * a drain running hourly cannot double-pay and cannot strand an instalment either.
     */
    @Transactional
    public void prepareRun(LocalDate runDate) {
        UUID tenantId = TenantContext.get();
        List<PayoutInstalment> ready = instalments.findStreamInstalmentsReadyForARun(tenantId);
        if (ready.isEmpty()) {
            return;
        }
        PaymentRun run = runs.findByTenantIdAndRunDate(tenantId, runDate)
            .orElseGet(() -> runs.save(new PaymentRun(tenantId, runDate)));
        if (!run.isPrepared()) {
            return;
        }
        for (PayoutInstalment i : ready) {
            i.assignToRun(run.getPaymentRunId());
            instalments.save(i);
        }
    }

    @Override
    @Transactional
    public PaymentRunView approveRun(UUID paymentRunId, String approver) {
        PaymentRun run = runs.findById(paymentRunId).orElseThrow(() -> new PayoutNotFoundException(paymentRunId));
        run.approve(approver);
        runs.save(run);
        for (PayoutInstalment i : instalments.findByPaymentRunIdOrderByDueDateAsc(paymentRunId)) {
            // The destination was confirmed by a person when the stream's first instalment was
            // reviewed, and it is reused rather than re-entered here. A batch approver typing a
            // payee per row would be the one place in this flow where money could be redirected
            // without a second pair of eyes.
            i.approveInRun(paymentRunId, approver, streamPayee(i.getStreamId()));
            instalments.save(i);
            publishPayoutRequested(i);
        }
        return runView(run);
    }

    /** The payee a person already confirmed on this stream, from the earliest instalment that has one. */
    private String streamPayee(UUID streamId) {
        return instalments.findByStreamIdOrderByDueDateAsc(streamId).stream()
            .map(PayoutInstalment::getPayeeRef)
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseThrow(() -> new PayoutStateException(
                "Stream " + streamId + " has no payee confirmed by a reviewer, so its run cannot be approved"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PaymentRunView> listRuns() {
        return runs.findByTenantIdOrderByRunDateDesc(TenantContext.get()).stream().map(this::runView).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentRunView getRun(UUID paymentRunId) {
        return runView(runs.findById(paymentRunId).orElseThrow(() -> new PayoutNotFoundException(paymentRunId)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayoutInstalmentView> runInstalments(UUID paymentRunId) {
        return instalments.findByPaymentRunIdOrderByDueDateAsc(paymentRunId).stream().map(Views::of).toList();
    }

    private PaymentRunView runView(PaymentRun run) {
        List<PayoutInstalment> members = instalments.findByPaymentRunIdOrderByDueDateAsc(run.getPaymentRunId());
        BigDecimal total = members.stream().map(PayoutInstalment::getCurrentAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = members.isEmpty() ? "TZS" : members.get(0).getCurrency();
        return new PaymentRunView(run.getPaymentRunId(), run.getRunDate(), run.getStatus(), run.getApprovedBy(),
            members.size(), total, currency);
    }

    /**
     * Proof of life is overdue: suspend the stream, and hold everything DUE on it.
     *
     * <p>An instalment already assigned to a run is left where it is. The batch was assembled while
     * the stream was still ACTIVE and will be approved or not as a batch; pulling one row out from
     * under it would make the total somebody is about to approve wrong.
     */
    @Transactional
    public void suspendStream(UUID streamId) {
        PayoutStream stream = streams.findById(streamId).orElseThrow(() -> new PayoutNotFoundException(streamId));
        stream.suspendForProofOfLife();
        streams.save(stream);
        for (PayoutInstalment i : instalments.findByStreamIdAndStatus(streamId, InstalmentStatus.DUE.name())) {
            if (i.getPaymentRunId() == null) {
                i.hold("Proof of life is overdue");
                instalments.save(i);
            }
        }
    }

    @Override
    @Transactional
    public void recordProofOfLife(UUID streamId, ProofOfLifeMethod method, UUID documentId, String recordedBy) {
        if (method == null) {
            throw new PayoutStateException("Proof of life needs a method");
        }
        PayoutStream stream = streams.findById(streamId).orElseThrow(() -> new PayoutNotFoundException(streamId));
        stream.recordProofOfLife(LocalDate.now());
        streams.save(stream);
        // Only what the overdue proof held comes back. An instalment held because the PREMIUMS are
        // behind stays held -- proving someone is alive says nothing about what they have paid.
        PremiumTally tally = tallies.findById(stream.getPolicyNumber()).orElse(null);
        for (PayoutInstalment i : instalments.findByStreamIdAndStatus(streamId, InstalmentStatus.ON_HOLD.name())) {
            if (tally == null || tally.isPaidUpTo(i.getDueDate())) {
                i.release();
                instalments.save(i);
            }
        }
    }

    /**
     * Withdraw everything dated {@code from} onwards, and end the policy's streams.
     *
     * <p>What is already ON_HOLD goes too, whatever its date: a held payout is one that fell due
     * while the customer was behind, and a policy that then lapses never cured those arrears. On a
     * death or surrender the same rows are replaced by the benefit being paid instead.
     *
     * <p>Nothing APPROVED or later is touched -- {@code cancel} is a no-op there, so an event
     * arriving after the money went out cannot fail the lifecycle transition that sent it.
     */
    @Transactional
    public void cancelFuture(String policyNumber, LocalDate from, String reason) {
        for (PayoutInstalment i : instalments.findByPolicyNumberAndDueDateGreaterThanEqual(policyNumber, from)) {
            if (i.cancel(reason)) {
                instalments.save(i);
            }
        }
        for (PayoutInstalment held : instalments.findByPolicyNumberAndStatus(policyNumber, InstalmentStatus.ON_HOLD.name())) {
            if (held.cancel(reason)) {
                instalments.save(held);
            }
        }
        streams.findByPolicyNumber(policyNumber).forEach(s -> {
            s.end();
            streams.save(s);
        });
    }

    /**
     * Reinstatement: instalments the lapse cancelled come back, but only those still AHEAD.
     *
     * <p>What fell due during the lapse stays forfeited -- the customer was not covered then, and
     * reviving a benefit for a period nobody paid for would pay for cover that did not exist.
     */
    @Transactional
    public void restoreAfterReinstatement(String policyNumber, LocalDate reinstatedOn) {
        for (PayoutInstalment i : instalments.findByPolicyNumberAndDueDateAfter(policyNumber, reinstatedOn)) {
            if (i.status() == InstalmentStatus.CANCELLED && LAPSE_REASON.equals(i.getStatusReason())) {
                i.restore();
                instalments.save(i);
            }
        }
        streams.findByPolicyNumber(policyNumber).forEach(s -> {
            s.reopen();
            streams.save(s);
        });
    }

    /** Paid-up: every payout still ahead shrinks by the same proportion the sum assured did. */
    @Transactional
    public void restateForPaidUp(String policyNumber, BigDecimal paidUpSa, BigDecimal originalSa) {
        String reason = "Made paid-up: " + paidUpSa.setScale(2, java.math.RoundingMode.HALF_EVEN)
            + " of " + originalSa.setScale(2, java.math.RoundingMode.HALF_EVEN) + " sum assured";
        for (PayoutInstalment i : instalments.findByPolicyNumberOrderByDueDateAscRowOrderAsc(policyNumber)) {
            if (i.getOriginalAmount() != null) {
                i.restate(PayoutArithmetic.restate(i.getOriginalAmount(), paidUpSa, originalSa), reason);
                instalments.save(i);
            }
        }
    }

    /**
     * What a death claim may pay on this policy, given the ceiling policy already computed.
     *
     * <p>Two product rules, both from the guide and both optional:
     * <ul>
     *   <li>§7 -- survival benefits already PAID may come off the death benefit. A product setting,
     *       not a universal rule, which is why the version must state it.</li>
     *   <li>§6 -- the death benefit may be the HIGHER of the sum assured and a percentage of the
     *       premiums paid, so a family is never paid less than what was put in.</li>
     * </ul>
     *
     * <p>Unchanged for a policy whose version authored no terms, which is every policy sold before
     * this module existed.
     */
    @Override
    @Transactional(readOnly = true)
    public BigDecimal deathBenefitCeiling(String policyNumber, BigDecimal sumAssuredCeiling) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        PayoutPlan plan = productApi.resolvePayoutPlan(policy.productVersionId());
        if (!plan.authored()) {
            return sumAssuredCeiling;
        }
        BigDecimal ceiling = sumAssuredCeiling;
        if (Boolean.TRUE.equals(plan.terms().survivalBenefitsDeductedFromDeath())) {
            BigDecimal paid = instalments.findByPolicyNumberAndStatus(policyNumber, InstalmentStatus.PAID.name()).stream()
                .filter(i -> i.kind() == PayoutKind.SURVIVAL)
                .map(PayoutInstalment::getCurrentAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            // Never below zero: a product that paid out more than the sum assured still owes nothing
            // rather than owing a negative amount.
            ceiling = ceiling.subtract(paid).max(BigDecimal.ZERO);
        }
        BigDecimal pct = plan.terms().deathBenefitPremiumPercent();
        if (pct != null) {
            BigDecimal collected = tallies.findById(policyNumber)
                .map(PremiumTally::getPremiumsCollected).orElse(BigDecimal.ZERO);
            ceiling = ceiling.max(PayoutArithmetic.percentOf(collected, pct));
        }
        return ceiling;
    }

    /** A suspended or ended stream's instalments stay held however current the premiums are. */
    boolean streamAllowsRelease(PayoutInstalment instalment) {
        return instalment.getStreamId() == null
            || streams.findById(instalment.getStreamId())
                .map(s -> s.status() == StreamStatus.ACTIVE || s.status() == StreamStatus.PENDING_ACTIVATION)
                .orElse(true);
    }

    PayoutInstalment load(UUID instalmentId) {
        return instalments.findById(instalmentId).orElseThrow(() -> new PayoutNotFoundException(instalmentId));
    }
}
