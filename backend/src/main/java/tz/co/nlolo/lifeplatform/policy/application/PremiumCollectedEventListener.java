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
 * Starts cover when billing confirms the first premium.
 *
 * <p>This is the other half of offer-and-acceptance. {@code PolicyApiImpl.issuePolicy} now leaves
 * an ordinary policy PROPOSED — an offer the customer has something to pay against — and this
 * listener is what turns paying into accepting. Payment IS the acceptance; there is no separate
 * "accept" button anywhere, because asking a customer to accept and then to pay is one act
 * recorded twice.
 *
 * <p>{@code billing.PremiumCollected} fires only when an invoice actually reaches PAID (see
 * {@code BillingApiImpl.recordPayment}: a partial payment does not qualify, and neither does a
 * waived invoice). So the event means the money genuinely arrived, which is exactly the
 * condition cover is waiting on.
 *
 * <p>AFTER_COMMIT with PROPAGATION_REQUIRES_NEW, for the reason
 * {@link UnderwritingDecisionEventListener} documents at length: at AFTER_COMMIT the producer's
 * transaction has physically committed but Spring has not unbound its resources, so a plain
 * REQUIRED call would silently join an already-committed transaction — the writes would run,
 * throw nothing, and never land anywhere. That exact failure was caught empirically on this
 * codebase, by that listener's first draft.
 *
 * <p>No module dependency on {@code billing} is created or needed: this reads an event envelope
 * off the application event bus, not a billing API.
 */
@Component
public class PremiumCollectedEventListener {

    private static final Logger log = LoggerFactory.getLogger(PremiumCollectedEventListener.class);

    private final PolicyApi policyApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PremiumCollectedEventListener(PolicyApi policyApi, PlatformTransactionManager transactionManager) {
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
            log.error("billing.PremiumCollected carried no policyNumber; cover cannot be started");
            return;
        }

        // Save and restore rather than clear, for the reason UnderwritingDecisionEventListener
        // documents: an AFTER_COMMIT listener runs synchronously on the SAME thread as whatever
        // committed billing's transaction, so an unconditional clear() would wipe a caller's own
        // still-in-use context.
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(
                status -> policyApi.activateOnFirstPremium(policyNumber));
        } catch (Exception e) {
            // The premium is collected either way -- billing has committed, and rolling that
            // back is not on offer here. Cover failing to start is the serious half, so it is
            // logged loudly rather than swallowed, and the next premium on the same policy will
            // activate it: activateOnFirstPremium is idempotent precisely so that retry is safe.
            log.error("Failed to start cover for policy {} after its premium was collected", policyNumber, e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
