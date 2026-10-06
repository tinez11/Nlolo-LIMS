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
 * Consumes {@code claims.ClaimApproved} and books what the reinsurer owes.
 *
 * <p><b>At APPROVAL since IFRS 17 I3c</b> (user answer Q4, posting guide B-05 "when: claim admitted on a reinsured
 * policy"): the claim is an incurred claim (LIC, 2211) from approval, so the reinsurer's share is an asset for
 * incurred claims (1420) from the same moment -- Dr 1420 / Cr 6120, posted by finaccounting from
 * {@code reinsurance.RecoveryCalculated}. M8 waited for settlement so a failed payment could not leave a recoverable
 * booked; under IFRS 17 the liability, and so the recovery, exist whether or not the money has left yet. Agreement
 * with the reinsurer happens on the statement, not by a Confirm here.
 *
 * <p>Only the INSURED part is recovered: the approved amount less its investment component, which no reinsurer
 * covers.
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
            case "claims.ClaimApproved" -> withTenant(envelope, this::handleClaimApproved);
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

    private void handleClaimApproved(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        UUID claimId = UUID.fromString(String.valueOf(payload.get("claimId")));
        String policyNumber = (String) payload.get("policyNumber");
        @SuppressWarnings("unchecked")
        Map<String, Object> approved = (Map<String, Object>) payload.get("approvedAmount");
        String settledCurrency = (String) approved.get("currencyCode");
        // The insured part only: the investment component is repaid in all circumstances and no reinsurer covers it.
        BigDecimal investmentComponent = payload.get("investmentComponent") == null ? BigDecimal.ZERO
            : new BigDecimal(String.valueOf(payload.get("investmentComponent")));
        BigDecimal settledAmount = new BigDecimal(String.valueOf(approved.get("amount")))
            .subtract(investmentComponent.max(BigDecimal.ZERO));
        if (settledAmount.signum() <= 0) {
            log.info("Claim {} on policy {} is all investment component -- nothing insured to recover", claimId, policyNumber);
            return;
        }

        Optional<PolicyProjection> maybeProjection =
            policyProjectionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        if (maybeProjection.isEmpty()) {
            log.info("Claim {} approved on policy {} which has no reinsurance projection row (pre-M8 policy) "
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
                    amount, settledCurrency, "system:claims.ClaimApproved"))
                .ifPresent(recovery -> publishRecoveryCalculated(tenantId, policyNumber, recovery));
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
                amount, settledCurrency, "system:claims.ClaimApproved"))
            .ifPresentOrElse(recovery -> publishRecoveryCalculated(tenantId, policyNumber, recovery),
                () -> log.info("Claim {} of {} {} falls within XOL treaty {}'s retention -- nothing recoverable",
                    claimId, settledAmount, settledCurrency, xol.getTreatyId()));
    }

    /** Matches asyncapi-events.yaml's RecoveryCalculatedPayload field-for-field. finaccounting posts it (B-05: Dr 1420
     * / Cr 6120) against the policy's IFRS 17 dimensions, hence {@code policyNumber} (IFRS 17 I3c). */
    private void publishRecoveryCalculated(UUID tenantId, String policyNumber, ClaimRecovery recovery) {
        eventPublisher.publishEvent(DomainEventEnvelope.of("reinsurance.RecoveryCalculated", tenantId,
            Map.of("recoveryId", recovery.getRecoveryId(),
                   "claimId", recovery.getClaimId(),
                   "policyNumber", policyNumber,
                   "treatyId", recovery.getTreatyId(),
                   "recoverableAmount", Map.of("amount", recovery.getRecoverableAmount().toPlainString(),
                                                "currencyCode", recovery.getRecoverableCurrency()))));
    }
}
