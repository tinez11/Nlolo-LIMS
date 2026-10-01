package tz.co.nlolo.lifeplatform.distribution.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionCalculator;
import tz.co.nlolo.lifeplatform.distribution.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.AgentProfileRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.CommissionAccrualRepository;
import tz.co.nlolo.lifeplatform.distribution.infrastructure.PolicyProjectionRepository;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Task 6: consumes {@code policy.PolicyActivated} (accrue) and {@code policy.PolicyLapsed} (claw
 * back within a window) -- the two events that turn Task 4's pure {@link CommissionCalculator}
 * into real {@code commission_accrual}/{@code commission_statement} rows. {@code policy} is not
 * in distribution's {@code allowedDependencies}, so everything here is built from event payloads
 * plus this module's own {@code policy_projection} table (V2 section 8), never from
 * {@code PolicyApi}.
 *
 * <p>Copies {@code claims/application/PaymentEventListener.java}'s mechanics: {@code
 * @TransactionalEventListener(AFTER_COMMIT)} (the producer's write -- {@code policy.policy} or
 * its lapse -- must be durable before distribution reacts), one reusable {@code
 * PROPAGATION_REQUIRES_NEW} {@link TransactionTemplate} (a plain {@code @Transactional} (REQUIRED)
 * method called from an AFTER_COMMIT callback silently joins the already-committed producer
 * transaction and never actually commits -- confirmed empirically on this codebase before), and
 * {@code TenantContext} save/set/restore (this runs synchronously on the SAME thread as whatever
 * committed policy's transaction, so an unconditional {@code clear()} would wipe a caller's own
 * still-in-use context).
 *
 * <p><b>Unlike {@code claims.application.PaymentEventListener}, each handler here uses ONE
 * transaction, not two.</b> Claims needs phase separation because its second phase calls a
 * FOREIGN module ({@code PolicyApi}) that can fail AFTER a financial fact (a settled claim, money
 * moved) is already durable, and that fact must never be rolled back by the foreign call's
 * precondition. Nothing here calls out to another module: {@code handlePolicyActivated} and {@code
 * handlePolicyLapsed} each only ever read/write this module's OWN tables ({@code
 * policy_projection}, {@code commission_accrual}, {@code commission_statement}) plus one read-only
 * call to {@code ReferenceDataApi.getValue} (a refdata lookup, not a mutation). So each handler is
 * correctly atomic as ONE local transaction: either the whole thing commits, or none of it does.
 *
 * <p><b>There is no {@code IN_DOUBT} boundary here, and that absence is deliberate, not an
 * oversight.</b> {@code payment}'s {@code IN_DOUBT} state (and {@code claims}' own javadoc
 * documenting it) exists because a mobile-money gateway call can time out or return an
 * indeterminate response, leaving money whose fate is genuinely unknown outside this platform.
 * Nothing in this class talks to an external rail or another module's write path -- {@code
 * ReferenceDataApi.getValue} either returns a value or throws, and either way the SAME local
 * transaction that read it is the one deciding whether to write an accrual, so a thrown exception
 * simply rolls the whole handler back (caught by {@link #withTenant}'s catch-all and logged) with
 * NOTHING partially applied. There is no analogous "we do not know if this happened" state for a
 * local Postgres write to land in.
 */
@Component("distributionPolicyEventListener")
public class PolicyEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventListener.class);

    /** Informational, not an error -- observability/alert-rules.yml's own comment block on the
     * matching rule explains why: a spike here means many early lapses, a distribution-quality
     * signal for the business, not a platform malfunction. */
    private static final String CLAWBACK_COUNTER = "lifeplatform_distribution_clawback_total";

    private final PolicyProjectionRepository policyProjectionRepository;
    private final AgentProfileRepository agentProfileRepository;
    private final CommissionAccrualRepository commissionAccrualRepository;
    private final DistributionApiImpl distributionApiImpl;
    private final ReferenceDataApi referenceDataApi;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyEventListener(PolicyProjectionRepository policyProjectionRepository,
                                AgentProfileRepository agentProfileRepository,
                                CommissionAccrualRepository commissionAccrualRepository,
                                DistributionApiImpl distributionApiImpl,
                                ReferenceDataApi referenceDataApi,
                                ApplicationEventPublisher eventPublisher,
                                MeterRegistry meterRegistry,
                                PlatformTransactionManager transactionManager) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.agentProfileRepository = agentProfileRepository;
        this.commissionAccrualRepository = commissionAccrualRepository;
        this.distributionApiImpl = distributionApiImpl;
        this.referenceDataApi = referenceDataApi;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            // PolicyActivated, not PolicyIssued. PolicyIssued now means "the contract record
            // exists" -- an offer awaiting its first premium. Accruing commission against a
            // proposal would mean paying agents for people who may never pay, then clawing it
            // back off them when the offer expires.
            case "policy.PolicyActivated" -> withTenant(envelope, this::handlePolicyActivated);
            case "policy.AgentOfRecordChanged" -> withTenant(envelope, this::handleAgentOfRecordChanged);
            case "policy.PolicyLapsed" -> withTenant(envelope, this::handlePolicyLapsed);
            case "policy.PolicyCancelledFreeLook" -> withTenant(envelope, this::handleFreeLookCancelled);
            // Credit life: commission follows the money that actually came in, file by file,
            // and goes back when any of it is refunded.
            case "policy.EnrolmentAccepted" -> withTenant(envelope, this::handleEnrolmentAccepted);
            case "billing.PremiumRefundDue" -> withTenant(envelope, this::handlePremiumRefundDue);
            default -> { /* not distribution-relevant */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            log.error("distribution failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /**
     * 1. Write the projection row first, unconditionally -- even when {@code agentOfRecordId} is
     * null: a direct-sold policy still needs its premium and issue date recorded, since this
     * table is the ONLY place distribution ever learns them (see its own V2 javadoc on why it
     * exists at all). 2. A null {@code agentOfRecordId} means direct-sold: log and return, no
     * error. 3. Resolve the seller's plan, walk ancestors, load each ancestor's OWN plan, call
     * {@link CommissionCalculator#calculate}. 4. Persist each accrual (Task 6's internal
     * primitive handles the open-statement bookkeeping and idempotency). 5. Publish {@code
     * distribution.CommissionAccrued} per accrual actually persisted (a redelivery persists
     * nothing, so publishes nothing -- consistent with every other idempotent listener on this
     * platform).
     */
    private void handlePolicyActivated(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        UUID productId = (UUID) payload.get("productId");
        LocalDate issueDate = LocalDate.parse((String) payload.get("issueDate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premium");
        BigDecimal premiumAmount = new BigDecimal((String) premium.get("amount"));
        String premiumCurrency = (String) premium.get("currencyCode");
        // Nullable by design -- policy.PolicyActivated's payload is a LinkedHashMap precisely so this
        // key can be absent/null without Map.of's NPE-on-null-value trap. A null here means the
        // policy was sold direct.
        UUID agentOfRecordId = (UUID) payload.get("agentOfRecordId");

        if (policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).isEmpty()) {
            policyProjectionRepository.save(new PolicyProjection(tenantId, policyNumber, agentOfRecordId,
                productId, premiumAmount, premiumCurrency, issueDate));
        }

        // A SINGLE-premium policy accrues nothing here, and this is the second half of a fact
        // billing already states: the premium on a credit-life master policy is a placeholder
        // that nobody agreed and nobody will ever pay. Billing refuses to invoice it; paying
        // commission on it would be worse, because that money leaves the business.
        //
        // The real premium arrives per accepted enrolment file -- see handleEnrolmentAccepted,
        // which is where a credit-life scheme's commission is actually earned. The projection
        // row above is still written, because the clawback path needs the agent of record.
        if ("SINGLE".equals(payload.get("premiumFrequency"))) {
            log.info("Policy {} is single-premium -- no commission at activation; it accrues per "
                + "accepted enrolment file", policyNumber);
            return;
        }
        // A redelivered PolicyActivated for a policy number already projected has nothing further to
        // update at issuance time (premium/issueDate/agent never change after issuance), so no
        // "else" branch is needed here -- unlike the accrual rows below, which DO need an explicit
        // idempotency guard because they are event-sourced line items, not a single upserted row.

        if (agentOfRecordId == null) {
            log.info("Policy {} was sold direct (no agentOfRecordId) -- no commission to accrue", policyNumber);
            return;
        }

        CommissionCalculator.AgentWithPlan seller = distributionApiImpl.resolveAgentWithPlan(tenantId, agentOfRecordId, productId);
        if (seller == null) {
            log.info("Policy {}'s agentOfRecordId {} does not resolve to an agent in tenant {} -- no commission accrued",
                policyNumber, agentOfRecordId, tenantId);
            return;
        }

        // parentOf is backed by the repository, tenant-scoped -- resolveAncestorIds itself is the
        // pure, cycle-safe walk (Task 4); this lambda is the only side-effecting part of it.
        Function<UUID, UUID> parentOf = id -> agentProfileRepository.findByAgentIdAndTenantId(id, tenantId)
            .map(AgentProfile::getHierarchyParentId)
            .orElse(null);
        List<UUID> ancestorIds = CommissionCalculator.resolveAncestorIds(agentOfRecordId, parentOf);

        // Position matters: ancestors.get(0) is paid OVERRIDE, ancestors.get(1) SUPERVISOR_OVERRIDE
        // (CommissionCalculator.calculate's own contract). Filtering out an unresolvable ancestor
        // instead of substituting a null-agent placeholder would shift a further ancestor into the
        // wrong tier -- so a vanished ancestor (should not happen; hierarchy_parent_id is a self-FK)
        // becomes "no plan, earns nothing" at ITS position, not a silently wrong payout elsewhere.
        List<CommissionCalculator.AgentWithPlan> ancestors = ancestorIds.stream()
            .map(id -> {
                CommissionCalculator.AgentWithPlan resolved = distributionApiImpl.resolveAgentWithPlan(tenantId, id, productId);
                return resolved != null ? resolved : new CommissionCalculator.AgentWithPlan(null, null, null);
            })
            .toList();

        List<CommissionCalculator.Accrual> accruals = CommissionCalculator.calculate(
            seller, ancestors, TierType.FIRST_YEAR, premiumAmount, premiumCurrency);

        String period = currentPeriod();
        for (CommissionCalculator.Accrual accrual : accruals) {
            // sourceRef = policyNumber for an issuance accrual (V2's own comment on the column),
            // shared by the seller's FIRST_YEAR row and every ancestor's OVERRIDE/SUPERVISOR_OVERRIDE
            // row -- safe because ux_commission_accrual_once is keyed on (agent, tier_type,
            // source_ref), and each agent/tier combination here is distinct.
            distributionApiImpl.persistAccrual(tenantId, accrual.agentId(), policyNumber, accrual.tierType(),
                    accrual.amount(), accrual.currency(), period, policyNumber, null, "system:policy.PolicyActivated")
                .ifPresent(saved -> publishCommissionAccrued(tenantId, saved));
        }
    }

    private void publishCommissionAccrued(UUID tenantId, CommissionAccrual accrual) {
        // Matches asyncapi-events.yaml's existing CommissionAccruedPayload field-for-field.
        Map<String, Object> payload = Map.of(
            "statementId", accrual.getStatementId(),
            "agentId", accrual.getAgentId(),
            "policyNumber", accrual.getPolicyNumber(),
            "amount", Map.of("amount", accrual.getAmount().toPlainString(), "currencyCode", accrual.getCurrency()));
        eventPublisher.publishEvent(DomainEventEnvelope.of("distribution.CommissionAccrued", tenantId, payload));
    }

    /**
     * 1. No projection row means a pre-M7 policy (issued before this listener existed, or one this
     * tenant never ran M7 accrual for) -- log and return, never throw. 2. Compare {@code lapsedAt}
     * against {@code issueDate + TZ_COMMISSION_CLAWBACK_MONTHS}; outside the window, nothing to
     * do. 3-4. Inside the window: every unreversed {@code FIRST_YEAR} accrual for this policy (in
     * practice exactly one -- the seller's; overrides are never clawed back, an explicit M7
     * decision, see {@link CommissionCalculator}'s own javadoc) gets a reversing accrual booked
     * into the CURRENTLY OPEN period's statement, REGARDLESS of whether the original accrual's own
     * statement is OPEN, CLOSED or PAID.
     *
     * <p><b>Why the original statement is never touched, for ANY status, not only PAID.</b> The
     * reversal is a NEW row living in a DIFFERENT statement (today's), so the original accrual's
     * own statement's accrual list is provably unchanged by this operation -- recomputing its
     * total from that same unchanged list would produce the identical number, at the cost of a
     * pointless write (and, for an OPEN one, an unnecessary optimistic-lock version bump). For a
     * PAID statement specifically, this is not merely unnecessary but load-bearing: {@link
     * DistributionApiImpl#persistAccrual} only ever calls {@code getOrCreateOpenStatement} for
     * TODAY's period, which is a genuinely different statement from an older, already-paid one, so
     * a PAID statement is architecturally never the one written to -- see that method's own
     * javadoc. The one case where "today's statement" and "the original's statement" coincide is
     * an in-window lapse in the SAME calendar period as issuance, while that statement is still
     * OPEN; there, {@code persistAccrual}'s own recompute (of what is, in that case, the one and
     * only affected statement) already reflects both rows net.
     */
    /**
     * A scheme's agent of record was corrected. The projection is what accrual reads, so it
     * follows -- and only it: accruals already booked stay with whoever earned them.
     */
    private void handleAgentOfRecordChanged(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        UUID agentId = (UUID) payload.get("agentOfRecordId");
        policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber).ifPresentOrElse(
            projection -> {
                projection.changeAgent(agentId);
                policyProjectionRepository.save(projection);
            },
            () -> log.info("Agent of record changed on {} which has no distribution projection row -- "
                + "nothing to follow", policyNumber));
    }

    private void handlePolicyLapsed(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        Instant lapsedAt = Instant.parse((String) payload.get("lapsedAt"));

        Optional<PolicyProjection> maybeProjection = policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (maybeProjection.isEmpty()) {
            log.info("Policy {} lapsed but has no distribution projection row (pre-M7 policy) -- nothing to claw back", policyNumber);
            return;
        }
        PolicyProjection projection = maybeProjection.get();
        projection.markLapsed(lapsedAt);
        policyProjectionRepository.save(projection);

        int windowMonths = Integer.parseInt(referenceDataApi.getValue("TZ_COMMISSION_CLAWBACK_MONTHS", "TZ"));
        LocalDate lapseDate = lapsedAt.atZone(ZoneOffset.UTC).toLocalDate();
        long monthsSinceIssue = Period.between(projection.getIssueDate(), lapseDate).toTotalMonths();
        if (monthsSinceIssue > windowMonths) {
            log.info("Policy {} lapsed {} months after issue, outside the {}-month clawback window (TZ_COMMISSION_CLAWBACK_MONTHS) "
                + "-- nothing to claw back", policyNumber, monthsSinceIssue, windowMonths);
            return;
        }

        List<CommissionAccrual> unreversedFirstYear = commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(tenantId, policyNumber, TierType.FIRST_YEAR);
        String currentPeriod = currentPeriod();
        for (CommissionAccrual original : unreversedFirstYear) {
            distributionApiImpl.persistAccrual(tenantId, original.getAgentId(), policyNumber, TierType.FIRST_YEAR,
                    original.getAmount().negate(), original.getCurrency(), currentPeriod,
                    original.getAccrualId().toString(), original.getAccrualId(), "system:policy.PolicyLapsed")
                .ifPresent(reversal -> {
                    meterRegistry.counter(CLAWBACK_COUNTER).increment();
                    log.info("Clawed back {} {} of FIRST_YEAR commission from agent {} for policy {} (lapsed {} month(s) "
                        + "after issue, within the {}-month window)", reversal.getAmount().abs(), reversal.getCurrency(),
                        reversal.getAgentId(), policyNumber, monthsSinceIssue, windowMonths);
                });
        }
    }

    /**
     * Free-look: the sale is undone from inception, so EVERY unreversed accrual goes back.
     *
     * <p>Two differences from a lapse, both following from that. There is no clawback window -- a
     * lapse has one because cover genuinely ran for a while and the agent earned something for
     * writing it; here the contract is treated as never having existed, so there is nothing to
     * keep. And every tier goes, not FIRST_YEAR alone, for the same reason.
     *
     * <p>The idempotency key is the original accrual's id, which is the lapse handler's own choice.
     * That is deliberate: a policy cannot be both lapsed and free-look cancelled, but if some
     * future path contrived it, the second clawback of the same accrual would be dropped rather
     * than taking the money off the agent twice.
     */
    private void handleFreeLookCancelled(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        String period = currentPeriod();
        for (CommissionAccrual original : commissionAccrualRepository
                .findByTenantIdAndPolicyNumberAndReversesAccrualIdIsNullAndAmountGreaterThan(
                    tenantId, policyNumber, BigDecimal.ZERO)) {
            distributionApiImpl.persistAccrual(tenantId, original.getAgentId(), policyNumber, original.getTierType(),
                    original.getAmount().negate(), original.getCurrency(), period,
                    original.getAccrualId().toString(), original.getAccrualId(),
                    "system:policy.PolicyCancelledFreeLook")
                .ifPresent(reversal -> {
                    meterRegistry.counter(CLAWBACK_COUNTER).increment();
                    log.info("Clawed back {} {} of {} commission from agent {} for policy {} cancelled in free-look",
                        reversal.getAmount().abs(), reversal.getCurrency(), original.getTierType(),
                        reversal.getAgentId(), policyNumber);
                });
        }
    }

    /**
     * A lender's file was accepted, so the premium it earned is real production.
     *
     * <p>This is where a credit-life scheme's commission is actually earned. The master policy
     * accrues nothing at activation (see {@link #handlePolicyActivated}) because its premium is a
     * placeholder; the money comes in file by file, every month, for the life of the scheme.
     *
     * <p>FIRST_YEAR on every file, and RENEWAL never. A single premium has no renewal — each
     * file is a fresh batch of borrowers being written for the first time, not the same cover
     * being paid for again. Treating the second file as a renewal would pay the lower tier on
     * new business.
     *
     * <p>{@code sourceRef} is the submission id, so a redelivered acceptance finds the accrual
     * already booked: {@code persistAccrual}'s own guard does the rest.
     */
    private void handleEnrolmentAccepted(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        UUID submissionId = (UUID) payload.get("submissionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> premium = (Map<String, Object>) payload.get("premium");
        BigDecimal premiumAmount = new BigDecimal((String) premium.get("amount"));
        String currency = (String) premium.get("currencyCode");

        if (premiumAmount.signum() == 0) {
            log.info("Enrolment submission {} enrolled nobody -- no commission to accrue", submissionId);
            return;
        }

        Optional<PolicyProjection> maybeProjection =
            policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (maybeProjection.isEmpty()) {
            log.info("Enrolment accepted on policy {} which has no distribution projection row "
                + "-- no commission accrued", policyNumber);
            return;
        }
        UUID agentId = maybeProjection.get().getAgentId();
        if (agentId == null) {
            log.info("Scheme {} was sold direct (no agent of record) -- no commission on its files",
                policyNumber);
            return;
        }

        // THE BACKSTOP to policy's own rule (spec 2.8): on credit life only the LENDER earns. A
        // scheme set up before that rule may still name another agent -- five did, all the
        // individual who had registered the lender -- and giving that agent a credit-life plan
        // would hand them every file's commission. Refused here as well, so it cannot.
        Object lender = payload.get("policyholderPartyId");
        if (lender != null) {
            UUID agentParty = agentProfileRepository.findById(agentId).map(a -> a.getPartyId()).orElse(null);
            if (!UUID.fromString(lender.toString()).equals(agentParty)) {
                log.warn("Scheme {}'s agent of record {} is not its lender -- only the lender earns on a "
                    + "credit-life scheme, so submission {} accrues no commission. Correct the scheme's "
                    + "earner to the lender, or leave it direct.", policyNumber, agentId, submissionId);
                return;
            }
        }

        CommissionCalculator.AgentWithPlan seller = distributionApiImpl.resolveAgentWithPlan(
            tenantId, agentId, maybeProjection.get().getProductId());
        if (seller == null) {
            log.info("Scheme {}'s agent {} does not resolve to an agent with a plan -- no "
                + "commission accrued on submission {}", policyNumber, agentId, submissionId);
            return;
        }

        // No ancestors: overrides on a bancassurance scheme wait for the broker/bancassurance
        // discriminator M7 deferred, and one corporate agent does not justify it (spec 2.8).
        List<CommissionCalculator.Accrual> accruals = CommissionCalculator.calculate(
            seller, List.of(), TierType.FIRST_YEAR, premiumAmount, currency);

        for (CommissionCalculator.Accrual accrual : accruals) {
            distributionApiImpl.persistAccrual(tenantId, accrual.agentId(), policyNumber,
                    accrual.tierType(), accrual.amount(), accrual.currency(), currentPeriod(),
                    submissionId.toString(), null, "system:policy.EnrolmentAccepted")
                .ifPresent(booked -> log.info("Accrued {} {} of {} commission to agent {} for "
                    + "enrolment submission {} on scheme {}", booked.getAmount(), booked.getCurrency(),
                    booked.getTierType(), booked.getAgentId(), submissionId, policyNumber));
        }
    }

    /**
     * The insurer gave premium back, so the commission paid on it comes back too.
     *
     * <p><b>Pro rata, not the whole accrual.</b> One borrower of four hundred settled early;
     * the rest are still on cover and the bank keeps what it earned on them. The reversal is
     * the accrual scaled by the share of that file's premium being refunded.
     *
     * <p><b>Deliberately NOT subject to {@code TZ_COMMISSION_CLAWBACK_MONTHS}.</b> That window
     * gates the LAPSE path and is right there: a policy that lapses in year four keeps its
     * first-year commission, because the insurer kept the premium. A refund is different in
     * kind — the money physically went back, in month forty as much as in month two — so
     * applying the window here would let the bank keep commission on premium the insurer no
     * longer has. This is why the clawback is not simply a second caller of
     * {@link #handlePolicyLapsed}.
     */
    private void handlePremiumRefundDue(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String policyNumber = (String) payload.get("policyNumber");
        UUID policyMemberId = (UUID) payload.get("policyMemberId");
        UUID submissionId = (UUID) payload.get("enrolmentSubmissionId");
        @SuppressWarnings("unchecked")
        Map<String, Object> refund = (Map<String, Object>) payload.get("amount");
        BigDecimal refunded = new BigDecimal((String) refund.get("amount"));
        BigDecimal filePremium = new BigDecimal((String) payload.get("filePremiumTotal"));
        log.info("Refund of {} on policy {} for submission {} (file total {}) -- looking for the "
            + "accrual to reverse", refunded.toPlainString(), policyNumber, submissionId,
            filePremium.toPlainString());

        // The accrual to reverse is the one booked for THAT file, found by the submission id it
        // was sourced from. Not "the policy's FIRST_YEAR accrual": a scheme has one per month,
        // for years, and reversing the wrong one would claw back against borrowers who are
        // still on cover.
        List<CommissionAccrual> forThisFile = commissionAccrualRepository
            .findByTenantIdAndPolicyNumberAndTierTypeAndReversesAccrualIdIsNull(
                tenantId, policyNumber, TierType.FIRST_YEAR)
            .stream().filter(a -> submissionId.toString().equals(a.getSourceRef())).toList();

        if (forThisFile.isEmpty()) {
            log.info("Premium of {} refunded on policy {} but enrolment submission {} accrued no "
                + "commission -- nothing to claw back", refunded.toPlainString(), policyNumber,
                submissionId);
            return;
        }

        String currentPeriod = currentPeriod();
        for (CommissionAccrual original : forThisFile) {
            // Scaled by the refunded SHARE of that file's premium. Rounded once, at the end, and
            // capped at the accrual: rounding across four hundred borrowers must never reverse
            // more than was ever booked, or a statement goes negative and is paid as a debt owed
            // BY the bank.
            BigDecimal share = refunded.divide(filePremium, 10, RoundingMode.HALF_UP);
            BigDecimal reversal = original.getAmount().multiply(share)
                .setScale(2, RoundingMode.HALF_UP)
                .min(original.getAmount());

            if (reversal.signum() <= 0) {
                // A refund so small that a cent of commission does not round out of it. Logged
                // rather than skipped in silence: "no reversal appeared" must never be a state
                // with no explanation anywhere.
                log.info("Refund of {} against submission {} on policy {} rounds to no commission "
                    + "reversal of accrual {} -- nothing clawed back", refunded.toPlainString(),
                    submissionId, policyNumber, original.getAccrualId());
                continue;
            }
            // sourceRef is the departing MEMBER, not the accrual being reversed. One file's
            // accrual is reversed once per borrower who settles early, so the member is what
            // makes each partial reversal distinct -- and what makes a redelivered exit find
            // its own reversal already booked rather than the first borrower's.
            distributionApiImpl.persistAccrual(tenantId, original.getAgentId(), policyNumber,
                    TierType.FIRST_YEAR, reversal.negate(), original.getCurrency(), currentPeriod,
                    policyMemberId.toString(), original.getAccrualId(),
                    "system:billing.PremiumRefundDue")
                .ifPresent(booked -> {
                    meterRegistry.counter(CLAWBACK_COUNTER).increment();
                    log.info("Clawed back {} {} of FIRST_YEAR commission from agent {} on scheme {}: "
                        + "{} of submission {}'s {} premium was refunded", booked.getAmount().abs(),
                        booked.getCurrency(), booked.getAgentId(), policyNumber,
                        refunded.toPlainString(), submissionId, filePremium.toPlainString());
                });
        }
    }

    private static String currentPeriod() {
        return YearMonth.now().toString();
    }
}
