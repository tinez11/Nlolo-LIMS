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

    private final PayoutInstalmentRepository instalments;
    private final PayoutStreamRepository streams;
    private final PremiumTallyRepository tallies;
    private final ProductApi productApi;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher eventPublisher;

    public BenefitPayoutApiImpl(PayoutInstalmentRepository instalments, PayoutStreamRepository streams,
                                PremiumTallyRepository tallies, ProductApi productApi, PolicyApi policyApi,
                                ApplicationEventPublisher eventPublisher) {
        this.instalments = instalments;
        this.streams = streams;
        this.tallies = tallies;
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

    /** A suspended stream's instalments stay held however current the premiums are. */
    boolean streamAllowsRelease(PayoutInstalment instalment) {
        return instalment.getStreamId() == null
            || streams.findById(instalment.getStreamId()).map(s -> s.status() != StreamStatus.SUSPENDED).orElse(true);
    }

    PayoutInstalment load(UUID instalmentId) {
        return instalments.findById(instalmentId).orElseThrow(() -> new PayoutNotFoundException(instalmentId));
    }
}
