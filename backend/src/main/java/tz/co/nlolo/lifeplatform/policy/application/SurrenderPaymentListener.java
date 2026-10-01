package tz.co.nlolo.lifeplatform.policy.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/**
 * Closes out a customer surrender when its payout returns (step 1, task 4). Consumes
 * {@code payment.DisbursementCompleted} / {@code payment.DisbursementFailed} for the
 * SURRENDER_PAYOUT purpose only, and marks the surrender request PAID or FAILED accordingly. An
 * IN_DOUBT disbursement publishes neither event (payment holds it for reconciliation), so the
 * request simply stays APPROVED until the outcome is known -- deliberately, since a blind retry
 * could pay twice.
 *
 * <p>Consuming payment's events across the module boundary is by event envelope only (a string type
 * and a Map), so it is not a compile-time dependency on {@code payment} -- the same arrangement
 * {@code claims} already uses for these exact events.
 */
@Component
public class SurrenderPaymentListener {

    private static final Logger log = LoggerFactory.getLogger(SurrenderPaymentListener.class);
    private static final String SURRENDER_PAYOUT = "SURRENDER_PAYOUT";

    private final PolicyApi policyApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public SurrenderPaymentListener(PolicyApi policyApi, PlatformTransactionManager transactionManager) {
        this.policyApi = policyApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if (!"payment.DisbursementCompleted".equals(type) && !"payment.DisbursementFailed".equals(type)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        if (!SURRENDER_PAYOUT.equals(payload.get("purpose"))) {
            return;
        }
        UUID surrenderRequestId = UUID.fromString((String) payload.get("sourceRef"));
        boolean paid = "payment.DisbursementCompleted".equals(type);

        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                if (paid) {
                    Object idObj = payload.get("disbursementId");
                    UUID disbursementId = idObj instanceof UUID u ? u : UUID.fromString((String) idObj);
                    policyApi.markSurrenderPaid(surrenderRequestId, disbursementId);
                } else {
                    policyApi.markSurrenderFailed(surrenderRequestId);
                }
            });
        } catch (Exception e) {
            log.error("Failed to close surrender request {} after its payout {}", surrenderRequestId,
                paid ? "completed" : "failed", e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
