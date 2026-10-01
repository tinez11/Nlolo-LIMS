package tz.co.nlolo.lifeplatform.billing.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Closes M5's request/confirm loop on the billing side: consumes payment's
 * {@code PaymentConfirmed}/{@code PaymentFailed} confirmation events
 * (docs/02-module-architecture.md:65) and drives {@code BillingApi.applyConfirmedPayment}
 * accordingly.
 *
 * <p>Mirrors {@code billing.application.PolicyEventListener}'s (and
 * {@code policyloan.application.PaymentEventListener}'s) exact mechanics -- AFTER_COMMIT (the
 * producer's, i.e. payment's, write must be durable before billing reacts), a brand-new
 * REQUIRES_NEW transaction via {@link TransactionTemplate} (a plain REQUIRED call here would
 * silently join the already-committed producer transaction and never actually commit -- the same
 * {@code TransactionRequiredException} family {@code payment.PaymentRequestListener}'s own
 * Javadoc documents), and TenantContext save/set/restore via {@code getOrNull()} (this listener
 * runs synchronously on the SAME thread as whatever committed payment's transaction, so an
 * unconditional {@code clear()} would wipe a caller's own still-in-use context).
 *
 * <p>Unlike {@code payment.PaymentRequestListener}, this handler does only local DB work -- no
 * outbound gateway call -- so the plain single-REQUIRES_NEW-wrapper shape (one call per event, not
 * split into multiple phases) is correct here, exactly as it is for {@code PolicyEventListener}
 * and policyloan's own {@code PaymentEventListener}.
 */
// Explicit bean name: policyloan.application.PaymentEventListener has the same simple class name,
// and Spring's default @Component naming (decapitalized simple name) collides across packages --
// ConflictingBeanDefinitionException, found empirically when the whole app context first booted
// with both listeners present.
@Component("billingPaymentEventListener")
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final BillingApi billingApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentEventListener(BillingApi billingApi, PlatformTransactionManager transactionManager) {
        this.billingApi = billingApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "payment.PaymentConfirmed" -> withTenant(envelope, this::handleConfirmed);
            case "payment.PaymentFailed" -> withTenant(envelope, this::handleFailed);
            default -> { /* not billing-relevant */ }
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
            log.error("billing failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handleConfirmed(Map<String, Object> payload) {
        if (!isPremium(payload)) {
            return;
        }
        UUID invoiceId = UUID.fromString((String) payload.get("sourceRef"));
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) payload.get("amount");
        BigDecimal paidAmount = new BigDecimal((String) amount.get("amount"));
        String currency = (String) amount.get("currencyCode");
        billingApi.applyConfirmedPayment(invoiceId, paidAmount, currency, (String) payload.get("gatewayReference"));
    }

    /** No state change: billing.sweep_billing_state() (Task 5) already escalates unpaid invoices
     * on its own timer regardless of any failed collection attempt, so a
     * payment.PaymentFailed carries no new invoice transition to perform here -- only an
     * operational trail that the attempt happened, for whoever investigates a stuck invoice. */
    /**
     * A top-up is a collection too (product step 3), and its sourceRef is a top-up id, not an
     * invoice -- parsed as one it would either throw or, worse, match nothing and be logged as a
     * failure billing does not own. Absent means PREMIUM: every confirmation before payment V9.
     */
    private static boolean isPremium(Map<String, Object> payload) {
        Object purpose = payload.get("purpose");
        return purpose == null || "PREMIUM".equals(purpose);
    }

    private void handleFailed(Map<String, Object> payload) {
        if (!isPremium(payload)) {
            return;
        }
        Object sourceRef = payload.get("sourceRef");
        log.warn("Payment collection failed for billing invoice {} (tenant {}): {}",
            sourceRef, TenantContext.get(), payload.get("reason"));
    }
}
