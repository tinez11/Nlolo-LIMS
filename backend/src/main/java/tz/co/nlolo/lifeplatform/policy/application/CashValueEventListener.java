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

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Recomputes a savings policy's cash value each time a premium is collected (step 1).
 *
 * <p>Separate from {@link PremiumCollectedEventListener}, which starts cover, because valuing the
 * policy and putting it on risk are independent jobs: one failing must not roll back the other. Both
 * consume {@code billing.PremiumCollected} on AFTER_COMMIT, each in its own REQUIRES_NEW
 * transaction. A pure-protection policy is a no-op inside {@code recalculateCashValue} -- it carries
 * no cash-value config -- so this listener runs for every policy but changes nothing for most.
 *
 * <p>The tenant is saved and restored rather than cleared, and the work runs in a new transaction,
 * for the reasons {@link PremiumCollectedEventListener} documents at length.
 */
@Component
public class CashValueEventListener {

    private static final Logger log = LoggerFactory.getLogger(CashValueEventListener.class);

    private final PolicyApi policyApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public CashValueEventListener(PolicyApi policyApi, PlatformTransactionManager transactionManager) {
        this.policyApi = policyApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        String policyNumber = (String) payload.get("policyNumber");
        if (policyNumber == null) {
            return;
        }
        String paidToRaw = (String) payload.get("paidToDate");
        LocalDate paidToDate = paidToRaw != null ? LocalDate.parse(paidToRaw) : null;

        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(
                status -> policyApi.recalculateCashValue(policyNumber, paidToDate));
        } catch (Exception e) {
            // The premium is collected regardless -- billing has committed. A cash value that fails
            // to recompute is recomputed on the next premium (the operation is idempotent), so it is
            // logged rather than allowed to poison the collection.
            log.error("Failed to recompute cash value for policy {} after a premium was collected", policyNumber, e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
