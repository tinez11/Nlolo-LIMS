package tz.co.nlolo.lifeplatform.policyloan.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policyloan.api.PolicyLoanApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Closes M5's request/confirm loop on the policyloan side: consumes payment's
 * {@code DisbursementCompleted}/{@code DisbursementFailed} confirmation events
 * (docs/02-module-architecture.md:65) and drives {@code PolicyLoanApi.markDisbursed}/
 * {@code markDisbursementFailed} accordingly.
 *
 * <p>Mirrors {@code billing.application.PolicyEventListener}'s exact mechanics -- AFTER_COMMIT
 * (the producer's, i.e. payment's, write must be durable first), a brand-new REQUIRES_NEW
 * transaction via {@link TransactionTemplate} (a plain REQUIRED call here would silently join
 * the already-committed producer transaction and never actually commit -- the same
 * {@code TransactionRequiredException} family {@code payment.PaymentRequestListener}'s own
 * Javadoc documents), and TenantContext save/set/restore (this runs synchronously on the SAME
 * thread as whatever committed payment's transaction).
 *
 * <p>Unlike {@code payment.PaymentRequestListener}, this handler does only local DB work -- no
 * outbound gateway call -- so the plain single-REQUIRES_NEW-wrapper shape (one call per event,
 * not split into multiple phases) is correct here, exactly as it is for
 * {@code PolicyEventListener}.
 *
 * <p>Injects {@link PolicyLoanApi} (the published interface), not the impl: unlike
 * {@code payment}'s own listener (which must call {@code PaymentApiImpl}'s package-private
 * methods because those are split across multiple transaction phases),
 * {@code markDisbursed}/{@code markDisbursementFailed} are public methods on
 * {@code PolicyLoanApi} -- this listener is simply another caller of the published API.
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PolicyLoanApi policyLoanApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentEventListener(PolicyLoanApi policyLoanApi, PlatformTransactionManager transactionManager) {
        this.policyLoanApi = policyLoanApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "payment.DisbursementCompleted" -> withTenant(envelope, this::handleCompleted);
            case "payment.DisbursementFailed" -> withTenant(envelope, this::handleFailed);
            default -> { /* not policyloan-relevant */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, java.util.function.Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            log.error("policyloan failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handleCompleted(Map<String, Object> payload) {
        if (!"LOAN_DISBURSEMENT".equals(payload.get("purpose"))) {
            return; // another module's payout rode the same event type
        }
        UUID loanId = UUID.fromString((String) payload.get("sourceRef"));
        policyLoanApi.markDisbursed(loanId, (String) payload.get("gatewayReference"),
            Instant.parse((String) payload.get("completedAt")));
    }

    private void handleFailed(Map<String, Object> payload) {
        if (!"LOAN_DISBURSEMENT".equals(payload.get("purpose"))) {
            return;
        }
        UUID loanId = UUID.fromString((String) payload.get("sourceRef"));
        // Compensating action per docs/03-aggregate-design.md:208: reverse the encumbrance so the
        // policyholder's loan value is not permanently consumed by a payout that never happened.
        policyLoanApi.markDisbursementFailed(loanId, (String) payload.get("reason"));
    }
}
