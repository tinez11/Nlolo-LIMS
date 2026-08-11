package tz.co.nlolo.lifeplatform.payment.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.payment.domain.GatewayException;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Request/Confirm consumer (docs/02-module-architecture.md:65). AFTER_COMMIT, so the
 * requesting module's own state change is durable before any money moves.
 *
 * <p><b>This listener deliberately does NOT wrap its work in one REQUIRES_NEW transaction</b>,
 * unlike billing.PolicyEventListener and policy.UnderwritingDecisionEventListener. Those hold a
 * transaction across their whole handler because their work is all local DB writes. Here the
 * work includes an external HTTP call, and holding a transaction open across it would be this
 * platform's first cross-network transaction hold. The sequence is instead three phases:
 * PaymentApiImpl.record*Request(...) commits on its own, THEN the gateway call runs with no
 * transaction, THEN complete-or-fail commits and publishes. Each @Transactional method on
 * PaymentApiImpl is its own boundary; this class orchestrates them.
 *
 * <p><b>Deviation from the task brief, found empirically, not by inspection alone:</b> the brief's
 * own sketch called PaymentApiImpl's package-private methods bare, relying on their plain
 * {@code @Transactional} (REQUIRED) to "commit on its own". Running the first draft against a
 * real Testcontainers Postgres immediately threw
 * {@code jakarta.persistence.TransactionRequiredException: no transaction is in progress} out of
 * {@code disbursementIdempotencyRepository.claimKey(...)} -- this is the exact same failure family
 * that {@code audit.infrastructure.DomainEventAuditListener} and
 * {@code policy.application.UnderwritingDecisionEventListener} already document independently: at
 * {@code AFTER_COMMIT} time, Spring's {@code TransactionSynchronizationManager} does not let a
 * plain REQUIRED {@code @Transactional} method open (or join) a real transaction the way it would
 * from an ordinary caller. Each of the three phases below is therefore run through its own
 * {@code executeWithoutResult}/{@code execute} call against a single reusable
 * PROPAGATION_REQUIRES_NEW {@link TransactionTemplate} -- one call per phase, so phase 1 and phase
 * 3 are still two genuinely separate committed transactions with nothing wrapping the gateway call
 * in between, exactly as required; only the mechanism for making each REQUIRED method actually
 * commit differs from the brief's literal text.
 *
 * <p>TenantContext is still saved/set/restored exactly as the other listeners do, and for the
 * same reason (this runs synchronously on the producer's thread — see
 * UnderwritingDecisionEventListener:81-90 for the empirically-caught bug an unconditional
 * clear() causes).
 *
 * <p>Failure posture differs from every other listener too. Elsewhere a caught exception is
 * logged and dropped, which is safe because audit has already durably recorded the raw event and
 * the missed side effect is manually recoverable. Here a dropped event means money never moved,
 * so a gateway failure is persisted as a FAILED row plus a published *Failed event — the
 * requesting module finds out and compensates. Only a failure to even record the request (a DB
 * error before the first commit) is logged and dropped, and in that case nothing was promised to
 * anyone: no row, no gateway call, no event.
 */
@Component
public class PaymentRequestListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestListener.class);

    private final PaymentApiImpl paymentApiImpl;
    private final PaymentGatewayPort gateway;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentRequestListener(PaymentApiImpl paymentApiImpl, PaymentGatewayPort gateway,
                                   PlatformTransactionManager transactionManager) {
        this.paymentApiImpl = paymentApiImpl;
        this.gateway = gateway;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policyloan.LoanDisbursementRequested" -> withTenant(envelope, this::handleLoanDisbursement);
            case "billing.PaymentRequested" -> withTenant(envelope, this::handlePremiumCollection);
            // claims.ClaimSettlementRequested (M6) and distribution.CommissionPayoutRequested
            // (M7) belong here as one case each once those modules exist and actually publish
            // them. purpose already has CLAIM_SETTLEMENT/COMMISSION_PAYOUT values; no branch is
            // written speculatively for a producer that does not exist (see Global Constraints).
            default -> { /* not payment-relevant */ }
        }
    }

    private void withTenant(DomainEventEnvelope<?> envelope, java.util.function.Consumer<Map<String, Object>> handler) {
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            handler.accept(payload);
        } catch (Exception e) {
            log.error("payment failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private void handleLoanDisbursement(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        UUID loanId = (UUID) payload.get("loanId");
        String payeeRef = (String) payload.get("payeeRef");
        if (payeeRef == null) {
            // policyloan allows originating a loan before a payee is known (its payload uses a
            // LinkedHashMap precisely because payeeRef is nullable). There is nowhere to send
            // money, so this is not a gateway failure — nothing is recorded and nothing is
            // promised. Logged loudly because a loan resting at DISBURSEMENT_REQUESTED forever
            // is a real operational condition someone must resolve.
            log.error("Cannot disburse loan {} for tenant {}: LoanDisbursementRequested carried no payeeRef", loanId, tenantId);
            return;
        }
        Money money = money(payload);
        Optional<UUID> disbursementId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordDisbursementRequest(
            tenantId, idempotencyKey, payeeRef, money.amount(), money.currency(), "LOAN_DISBURSEMENT", loanId.toString()));
        disbursementId.ifPresentOrElse(
            id -> submitDisbursement(tenantId, id, payeeRef, money),
            () -> log.info("Dropping duplicate LoanDisbursementRequested for tenant {} key {}", tenantId, idempotencyKey));
    }

    private void handlePremiumCollection(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        UUID invoiceId = (UUID) payload.get("invoiceId");
        String payerRef = (String) payload.get("payerRef");
        Money money = money(payload);
        Optional<UUID> transactionId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordCollectionRequest(
            tenantId, idempotencyKey, payerRef, money.amount(), money.currency(), invoiceId.toString()));
        transactionId.ifPresentOrElse(
            id -> submitCollection(tenantId, id, payerRef, money),
            () -> log.info("Dropping duplicate PaymentRequested for tenant {} key {}", tenantId, idempotencyKey));
    }

    /** Phase 2 (no transaction) + phase 3 (fresh transaction). */
    private void submitDisbursement(UUID tenantId, UUID disbursementId, String payeeRef, Money money) {
        try {
            PaymentGatewayPort.GatewayResult result = gateway.submitDisbursement(
                new PaymentGatewayPort.GatewayDisbursementRequest(
                    payeeRef, money.amount(), money.currency(), disbursementId.toString()));
            if (result.accepted()) {
                requiresNewTransactionTemplate.executeWithoutResult(status ->
                    paymentApiImpl.completeDisbursement(tenantId, disbursementId, result.gatewayReference()));
            } else {
                requiresNewTransactionTemplate.executeWithoutResult(status ->
                    paymentApiImpl.failDisbursement(tenantId, disbursementId, result.failureReason()));
            }
        } catch (GatewayException e) {
            // Transport failure. The instruction is already committed as PENDING, so this is
            // recorded as FAILED and announced -- never left as a silent PENDING row nobody
            // will ever look at again.
            log.error("Gateway transport failure disbursing {} for tenant {}", disbursementId, tenantId, e);
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                paymentApiImpl.failDisbursement(tenantId, disbursementId, "GATEWAY_UNAVAILABLE"));
        }
    }

    private void submitCollection(UUID tenantId, UUID transactionId, String payerRef, Money money) {
        try {
            PaymentGatewayPort.GatewayResult result = gateway.submitCollection(
                new PaymentGatewayPort.GatewayCollectionRequest(
                    payerRef, money.amount(), money.currency(), transactionId.toString()));
            if (result.accepted()) {
                requiresNewTransactionTemplate.executeWithoutResult(status ->
                    paymentApiImpl.confirmCollection(tenantId, transactionId, result.gatewayReference()));
            } else {
                requiresNewTransactionTemplate.executeWithoutResult(status ->
                    paymentApiImpl.failCollection(tenantId, transactionId, result.failureReason()));
            }
        } catch (GatewayException e) {
            log.error("Gateway transport failure collecting {} for tenant {}", transactionId, tenantId, e);
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                paymentApiImpl.failCollection(tenantId, transactionId, "GATEWAY_UNAVAILABLE"));
        }
    }

    private record Money(BigDecimal amount, String currency) {}

    private static Money money(Map<String, Object> payload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> amount = (Map<String, Object>) payload.get("amount");
        return new Money(new BigDecimal((String) amount.get("amount")), (String) amount.get("currencyCode"));
    }

    /** Fails loudly rather than inventing a key. docs/02:140 calls the key mandatory on every
     * inbound event; DomainEventEnvelope.eventId() is regenerated per publication and so is
     * useless as a dedup key. Producers derive a stable key from their own aggregate id. */
    private static String requireKey(Map<String, Object> payload) {
        String idempotencyKey = (String) payload.get("idempotencyKey");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Inbound payment request carried no idempotencyKey");
        }
        return idempotencyKey;
    }
}
