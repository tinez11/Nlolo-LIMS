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
 */
@Component("reinsuranceClaimEventListener")
public class ClaimEventListener {

    private static final Logger log = LoggerFactory.getLogger(ClaimEventListener.class);

    private final ReinsurancePolicyProjectionRepository policyProjectionRepository;
    private final CessionRepository cessionRepository;
    private final ReinsuranceApiImpl reinsuranceApiImpl;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ClaimEventListener(ReinsurancePolicyProjectionRepository policyProjectionRepository,
                               CessionRepository cessionRepository,
                               ReinsuranceApiImpl reinsuranceApiImpl,
                               ApplicationEventPublisher eventPublisher,
                               PlatformTransactionManager transactionManager) {
        this.policyProjectionRepository = policyProjectionRepository;
        this.cessionRepository = cessionRepository;
        this.reinsuranceApiImpl = reinsuranceApiImpl;
        this.eventPublisher = eventPublisher;
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

        // PATH 1 -- the policy was ceded at issuance (QUOTA_SHARE or SURPLUS): the reinsurer's
        // share of this loss is the same fraction it took of the sum assured.
        List<Cession> cessions = cessionRepository.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
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
