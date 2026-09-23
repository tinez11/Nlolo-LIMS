package tz.co.nlolo.lifeplatform.reinsurance.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.domain.RecoveryCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.CessionRepository;
import tz.co.nlolo.lifeplatform.reinsurance.infrastructure.ReinsurancePolicyProjectionRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumes the M8-enriched {@code claims.ClaimSettled} and books what the reinsurer owes.
 *
 * <p>Triggering on SETTLED rather than APPROVED is deliberate: a recoverable is a real receivable,
 * and approval precedes the payment rail -- a settlement can still fail (PAYOUT_FAILED) or land
 * IN_DOUBT, and booking a recoverable against money that never left would overstate assets. That
 * is why M8 enriched {@code ClaimSettled} (which carried only claimId + settledAt) rather than
 * consuming {@code ClaimApproved}, the same trade-off M7 resolved the same way for
 * {@code billing.PremiumCollected}.
 *
 * <p>Mechanics and bean-naming rationale: see {@link PolicyEventListener}.
 *
 * <p><b>Known gap (I2, final review) -- XOL recovery resolution is not pinned to the treaty in
 * force at issuance.</b> For QUOTA_SHARE/SURPLUS, the {@link Cession} row written at issuance
 * pins which treaty applies, so this listener's recovery-time lookup is stable and correct. For
 * XOL, there is deliberately no cession (§2.3/§6 of the design spec -- XOL does not cede at
 * issuance), so PATH 2 below re-runs {@code selectApplicableTreaty} at CLAIM-SETTLEMENT time and
 * requires the result to be XOL. This can resolve a DIFFERENT treaty than was actually in force
 * when the policy was issued: for example, if a newer QUOTA_SHARE (or another XOL) treaty was
 * authored later with a later {@code effectiveFrom} that also covers the original issue date, that
 * newer treaty now wins the lookup -- either silently changing which treaty is credited, or, if
 * the newer treaty is not XOL, causing the claim to recover NOTHING under the XOL cover that was
 * genuinely in force at issuance. This is a genuine architectural gap, not a quick fix: the real
 * fix is persisting the issuance-time-resolved {@code treaty_id} on {@code policy_projection} (a
 * migration + a producer change here + a consumer change here), which is deliberately NOT done in
 * this fix wave -- see the design spec's §8 for the recorded deferral. A future milestone should
 * persist the issuance-time-resolved treaty id rather than re-resolving it at claim time.
 */
@Component("reinsuranceClaimEventListener")
public class ClaimEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventListener.class);

    /** Final review (I5): the catch-all in {@link #withTenant} used to be log-only, so a listener
     * failure (a malformed payload, an unexpected runtime exception) was invisible to anything but
     * someone reading logs after the fact. Same naming convention as {@code
     * claims.application.PaymentEventListener}'s counters; tagged with the event type so a failure
     * can be attributed to a specific producer without grepping logs first. */
    private static final String EVENT_PROCESSING_FAILED_COUNTER = "lifeplatform_reinsurance_event_processing_failed_total";

    private final ReinsurancePolicyProjectionRepository policyProjectionRepository;
    private final CessionRepository cessionRepository;
    private final ReinsuranceApiImpl reinsuranceApiImpl;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ClaimEventListener(ReinsurancePolicyProjectionRepository policyProjectionRepository,
                               CessionRepository cessionRepository,
                               ReinsuranceApiImpl reinsuranceApiImpl,
                               ApplicationEventPublisher eventPublisher,
                               MeterRegistry meterRegistry,
                               PlatformTransactionManager transactionManager) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.cessionRepository = cessionRepository;
        this.reinsuranceApiImpl = reinsuranceApiImpl;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "claims.ClaimSettled" -> withTenant(envelope, this::handleClaimSettled);
            default -> { /* not reinsurance-relevant */ }
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
            meterRegistry.counter(EVENT_PROCESSING_FAILED_COUNTER, "eventType", envelope.eventType()).increment();
            log.error("reinsurance failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handleClaimSettled(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        UUID claimId = (UUID) payload.get("claimId");
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> settled = (Map<String, Object>) payload.get("settledAmount");
        BigDecimal settledAmount = new BigDecimal((String) settled.get("amount"));
        String settledCurrency = (String) settled.get("currencyCode");

        Optional<PolicyProjection> maybeProjection =
            policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (maybeProjection.isEmpty()) {
            log.info("Claim {} settled on policy {} which has no reinsurance projection row (pre-M8 policy) "
                + "-- nothing to recover", claimId, policyNumber);
            return;
        }
        PolicyProjection projection = maybeProjection.get();

        // A SCHEME IS NOT RECOVERED AGAINST, FOR THE SAME REASON IT IS NOT CEDED.
        //
        // PolicyEventListener already refuses to cede a group or credit-life scheme: its sum
        // assured is the total of a member schedule rather than one life, and the treaty model
        // cannot express classes of business, per-scheme provisions, or a cession that follows a
        // declining insured amount (design spec 0a, client answers of 2026-09-22). That guard was
        // load-bearing on the cession side and absent here, and PATH 2 below is the hole it left:
        // XOL recovers the excess of a loss over retention WITHOUT ANY CESSION, so "was never
        // ceded" is not the disqualifier it looks like. A settled credit-life claim would have
        // recovered against a treaty nobody agreed covered it, and finaccounting would have
        // booked the recoverable as a real asset against a real reinsurer.
        //
        // Unreachable until the credit-life claim chain existed -- no scheme claim could settle
        // before it -- which is exactly why it survived review on the cession side.
        //
        // The category comes off this module's own projection row (reinsurance/V4), not off the
        // event: the fact belongs to the policy, and reading it here from a payload would leave
        // the two listeners deciding the same question from two sources.
        if (projection.isScheme()) {
            log.info("Claim {} settled on {} scheme {} -- schemes are neither ceded nor recovered "
                + "against, so nothing is recoverable. Enabling it needs a per-life treaty basis "
                + "this module does not have.", claimId, projection.getProductCategory(), policyNumber);
            return;
        }

        // PATH 1 -- the policy was ceded at issuance (QUOTA_SHARE or SURPLUS): the reinsurer's
        // share of this loss is the same fraction it took of the sum assured.
        // Ordered by createdAt so which row is picked is deterministic rather than dependent on
        // database row order -- moot today under the one-treaty-per-policy design (at most one
        // cession row can exist per policy at all, backstopped by ux_cession_once), but cheap
        // insurance if that invariant is ever relaxed (M5, final review).
        List<Cession> cessions = cessionRepository.findByTenantIdAndPolicyNumberOrderByCreatedAtAsc(tenantId, policyNumber);
        if (!cessions.isEmpty()) {
            Cession cession = cessions.get(0);   // one treaty per policy -- see selectApplicableTreaty
            RecoveryCalculator.proportional(cession, projection.getSumAssuredAmount(), settledAmount, settledCurrency)
                .flatMap(amount -> reinsuranceApiImpl.persistRecovery(tenantId, claimId, cession.getTreatyId(),
                    amount, settledCurrency, "system:claims.ClaimSettled"))
                .ifPresent(recovery -> publishRecoveryCalculated(tenantId, recovery));
            return;
        }

        // PATH 2 -- no cession, so check for an XOL treaty, which cedes nothing at issuance BY
        // DESIGN and recovers the excess of the loss over retention instead. Without this branch
        // an XOL treaty would never recover anything and the type would be inert.
        Optional<ReinsuranceTreaty> maybeXol =
            reinsuranceApiImpl.selectApplicableTreaty(tenantId, projection.getIssueDate())
                .filter(t -> t.getTreatyType() == TreatyType.XOL);
        if (maybeXol.isEmpty()) {
            log.info("Claim {} settled on policy {} which was never ceded and has no applicable XOL treaty "
                + "-- nothing to recover", claimId, policyNumber);
            return;
        }
        ReinsuranceTreaty xol = maybeXol.get();
        RecoveryCalculator.excessOfLoss(xol, settledAmount, settledCurrency)
            .flatMap(amount -> reinsuranceApiImpl.persistRecovery(tenantId, claimId, xol.getTreatyId(),
                amount, settledCurrency, "system:claims.ClaimSettled"))
            .ifPresentOrElse(recovery -> publishRecoveryCalculated(tenantId, recovery),
                () -> log.info("Claim {} of {} {} falls within XOL treaty {}'s retention -- nothing recoverable",
                    claimId, settledAmount, settledCurrency, xol.getTreatyId()));
    }

    /** Matches asyncapi-events.yaml's RecoveryCalculatedPayload field-for-field. */
    private void publishRecoveryCalculated(UUID tenantId, ClaimRecovery recovery) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("reinsurance.RecoveryCalculated", tenantId,
            Map.of("recoveryId", recovery.getRecoveryId(),
                   "claimId", recovery.getClaimId(),
                   "treatyId", recovery.getTreatyId(),
                   "recoverableAmount", Map.of("amount", recovery.getRecoverableAmount().toPlainString(),
                                                "currencyCode", recovery.getRecoverableCurrency()))));
    }
}
