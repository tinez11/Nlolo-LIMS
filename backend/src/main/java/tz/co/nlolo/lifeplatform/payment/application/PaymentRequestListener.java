package tz.co.nlolo.lifeplatform.payment.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.payment.api.DisbursementMethod;
import tz.co.nlolo.lifeplatform.payment.domain.GatewayException;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentGatewayPort;
import io.micrometer.core.instrument.MeterRegistry;
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
 * so a gateway outcome is always persisted, never left in a log line alone. Only a failure to even
 * record the request (a DB error before the first commit) is logged and dropped, and in that case
 * nothing was promised to anyone: no row, no gateway call, no event.
 *
 * <p><b>Three outcomes, not two (review fix C2).</b> The plan's Global Constraints said "a gateway
 * failure must land the row in FAILED with a published *Failed event" -- correct for a rail that
 * DECLINES, and wrong for a rail that says nothing:
 * <ul>
 *   <li><b>accepted</b> -> COMPLETED/CONFIRMED + {@code *Completed}/{@code *Confirmed} event.</li>
 *   <li><b>explicitly declined</b> (a business rejection like INSUFFICIENT_FLOAT) -> FAILED +
 *       {@code *Failed} event, so the requesting module compensates. Unchanged.</li>
 *   <li><b>indeterminate</b> ({@link GatewayException}: read timeout, 5xx, empty body, or ACCEPTED
 *       with no gatewayReference) -> IN_DOUBT, and NO event at all. Publishing {@code *Failed} here
 *       drove a real REVERSAL plus encumbrance release in policyloan for a payout that may have
 *       succeeded. The signal instead is an ERROR log plus the
 *       {@code lifeplatform_payment_in_doubt_total} counter -- see {@link #recordIndeterminate}.</li>
 * </ul>
 * Routing on the right condition matters: the decline case is {@code !result.accepted()}, the
 * indeterminate case is {@code catch (GatewayException)}. Collapsing the two back together
 * reintroduces the bug in whichever direction it is collapsed.
 */
@Component
public class PaymentRequestListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestListener.class);

    /** Review fix (C2): the alertable signal for an IN_DOUBT outcome, which by design publishes no
     * domain event at all and would otherwise be invisible to anyone not reading logs line by line.
     * Tagged {@code kind=disbursement|collection} so the two money directions can be alerted on
     * separately if ever needed, while the untagged total still works in a single alert expression.
     * Follows MobileMoneyGatewayAdapter's and MobileMoneyCallbackController's existing
     * {@code lifeplatform_payment_*_total} naming convention; observability/alert-rules.yml carries
     * the matching rule. */
    private static final String IN_DOUBT_COUNTER = "lifeplatform_payment_in_doubt_total";

    private final PaymentApiImpl paymentApiImpl;
    private final PaymentGatewayPort gateway;
    private final MeterRegistry meterRegistry;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PaymentRequestListener(PaymentApiImpl paymentApiImpl, PaymentGatewayPort gateway,
                                   MeterRegistry meterRegistry,
                                   PlatformTransactionManager transactionManager) {
        this.paymentApiImpl = paymentApiImpl;
        this.gateway = gateway;
        this.meterRegistry = meterRegistry;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policyloan.LoanDisbursementRequested" -> withTenant(envelope, this::handleLoanDisbursement);
            case "billing.PaymentRequested" -> withTenant(envelope, this::handlePremiumCollection);
            case "claims.ClaimSettlementRequested" -> withTenant(envelope, this::handleClaimSettlement);
            case "distribution.CommissionPayoutRequested" -> withTenant(envelope, this::handleCommissionPayout);
            case "policy.SurrenderPayoutRequested" -> withTenant(envelope, this::handleSurrenderPayout);
            case "benefitpayout.PayoutRequested" -> withTenant(envelope, this::handleBenefitPayout);
            case "accumulation.PayoutRequested" -> withTenant(envelope, this::handleAccountPayout);
            // Unit-linked (product step 6): the same shape -- purpose, sourceRef, idempotencyKey, payee, amount.
            case "unitlinked.PayoutRequested" -> withTenant(envelope, this::handleAccountPayout);
            case "accumulation.TopUpRequested" -> withTenant(envelope, this::handleTopUpCollection);
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

    /** M6: claims' settlement payout. Mirrors {@link #handleLoanDisbursement} exactly -- the
     * request is committed before the rail is called, the rail call runs in NO transaction, and
     * the outcome lands in its own transaction. {@code purpose=CLAIM_SETTLEMENT} is already an
     * allowed value in {@code disbursement_instruction.purpose}'s CHECK constraint.
     *
     * <p>Unlike {@code handleLoanDisbursement}, no {@code payeeRef == null} guard is needed here:
     * {@code claims.ClaimsApiImpl.decideSettlement} already rejects a blank {@code payeeRef}
     * before this event is ever published, so a null payee cannot legitimately arrive on this
     * path -- reproducing that guard here would be dead code, not defense in depth. */
    private void handleClaimSettlement(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        UUID claimId = (UUID) payload.get("claimId");
        String payeeRef = (String) payload.get("payeeRef");
        Money money = money(payload);
        // Which rail, named by the publisher. Absent means MOBILE_MONEY — every publisher
        // before credit life, whose behaviour is unchanged.
        DisbursementMethod method = payload.get("disbursementMethod") == null
            ? DisbursementMethod.MOBILE_MONEY
            : DisbursementMethod.valueOf((String) payload.get("disbursementMethod"));

        Optional<UUID> disbursementId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordDisbursementRequest(
            tenantId, idempotencyKey, payeeRef, money.amount(), money.currency(), "CLAIM_SETTLEMENT",
            claimId.toString(), method));
        disbursementId.ifPresentOrElse(
            id -> {
                if (method == DisbursementMethod.EFT) {
                    // RECORDED, NOT SENT. There is no rail to call: finance moves this in the
                    // bank's own portal and confirms it here afterwards. Calling the gateway
                    // would put a multi-million-shilling lender payout through a mock with no
                    // authentication, which is the entire reason EFT exists.
                    log.info("Disbursement {} for claim {} is EFT -- recorded awaiting execution, "
                        + "not submitted to any gateway", id, claimId);
                    return;
                }
                submitDisbursement(tenantId, id, payeeRef, money);
            },
            () -> log.info("Dropping duplicate ClaimSettlementRequested for tenant {} key {}", tenantId, idempotencyKey));
    }

    /** M7: distribution's commission payout to an agent. Structurally identical to {@link
     * #handleClaimSettlement} -- request committed, rail called outside any transaction, outcome
     * committed separately -- with {@code statementId} in place of {@code claimId}.
     * {@code purpose=COMMISSION_PAYOUT} is already an allowed value in {@code
     * disbursement_instruction.purpose}'s CHECK constraint (payment/V1:54-55, re-asserted in
     * payment/V2), so M7 adds no payment migration.
     *
     * <p>No {@code payeeRef} null-guard, for the same reason {@code handleClaimSettlement} has
     * none: {@code CommissionStatement.markPayoutRequested} rejects a blank {@code payeeRef} (and a
     * blank idempotency key, and a non-positive total) before the event is ever published, so a
     * null payee cannot legitimately arrive here. */
    private void handleCommissionPayout(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        UUID statementId = (UUID) payload.get("statementId");
        String payeeRef = (String) payload.get("payeeRef");
        Money money = money(payload);
        Optional<UUID> disbursementId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordDisbursementRequest(
            tenantId, idempotencyKey, payeeRef, money.amount(), money.currency(), "COMMISSION_PAYOUT", statementId.toString()));
        disbursementId.ifPresentOrElse(
            id -> submitDisbursement(tenantId, id, payeeRef, money),
            () -> log.info("Dropping duplicate CommissionPayoutRequested for tenant {} key {}", tenantId, idempotencyKey));
    }

    /** A customer surrender payout. Structurally identical to {@link #handleClaimSettlement}, with
     *  the surrender request id as the source reference and purpose SURRENDER_PAYOUT (an
     *  already-allowed value in disbursement_instruction.purpose). */
    private void handleSurrenderPayout(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        String surrenderRequestId = (String) payload.get("surrenderRequestId");
        String payeeRef = (String) payload.get("payeeRef");
        Money money = money(payload);
        Optional<UUID> disbursementId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordDisbursementRequest(
            tenantId, idempotencyKey, payeeRef, money.amount(), money.currency(), "SURRENDER_PAYOUT", surrenderRequestId));
        disbursementId.ifPresentOrElse(
            id -> submitDisbursement(tenantId, id, payeeRef, money),
            () -> log.info("Dropping duplicate SurrenderPayoutRequested for tenant {} key {}", tenantId, idempotencyKey));
    }

    /**
     * Money out of a savings account (product step 3): a partial withdrawal, or a surrender valued
     * by the account. The twin of {@link #handleBenefitPayout} -- the publisher names the purpose
     * and the source reference, so a surrender's disbursement still carries the surrender request id
     * policy's SurrenderPaymentListener marks PAID.
     */
    private void handleAccountPayout(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        String purpose = (String) payload.get("purpose");
        String sourceRef = (String) payload.get("sourceRef");
        String payeeRef = (String) payload.get("payeeRef");
        Money money = money(payload);
        Optional<UUID> disbursementId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordDisbursementRequest(
            tenantId, idempotencyKey, payeeRef, money.amount(), money.currency(), purpose, sourceRef));
        disbursementId.ifPresentOrElse(
            id -> submitDisbursement(tenantId, id, payeeRef, money),
            () -> log.info("Dropping duplicate accumulation.PayoutRequested for tenant {} key {}", tenantId, idempotencyKey));
    }

    /** A top-up: a collection that is not a premium, so billing leaves its confirmation alone. */
    private void handleTopUpCollection(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        String topUpId = String.valueOf(payload.get("topUpId"));
        String payerRef = (String) payload.get("payerRef");
        Money money = money(payload);
        Optional<UUID> transactionId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordCollectionRequest(
            tenantId, idempotencyKey, payerRef, money.amount(), money.currency(), topUpId, "ACCOUNT_TOP_UP"));
        transactionId.ifPresentOrElse(
            id -> submitCollection(tenantId, id, payerRef, money),
            () -> log.info("Dropping duplicate accumulation.TopUpRequested for tenant {} key {}", tenantId, idempotencyKey));
    }

    /**
     * A scheduled benefit payout or a free-look refund (product step 2). Structurally the twin of
     * {@link #handleSurrenderPayout}, with two differences: the PUBLISHER names the purpose, because
     * one module now sends four kinds of money out; and the source reference is whichever id the
     * payload carries -- an instalment for a payout, a cancellation for a refund.
     */
    private void handleBenefitPayout(Map<String, Object> payload) {
        UUID tenantId = TenantContext.get();
        String idempotencyKey = requireKey(payload);
        String sourceRef = payload.get("instalmentId") != null
            ? (String) payload.get("instalmentId") : (String) payload.get("cancellationId");
        String purpose = (String) payload.get("purpose");
        String payeeRef = (String) payload.get("payeeRef");
        Money money = money(payload);
        Optional<UUID> disbursementId = requiresNewTransactionTemplate.execute(status -> paymentApiImpl.recordDisbursementRequest(
            tenantId, idempotencyKey, payeeRef, money.amount(), money.currency(), purpose, sourceRef));
        disbursementId.ifPresentOrElse(
            id -> submitDisbursement(tenantId, id, payeeRef, money),
            () -> log.info("Dropping duplicate PayoutRequested for tenant {} key {}", tenantId, idempotencyKey));
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
                // Defaulted, not passed through raw: PaymentApiImpl.failDisbursement builds its
                // published event payload with Map.of(..., "reason", reason), which throws NPE on
                // a null value -- a rail that declines without populating its own "reason" field
                // must never turn into an uncaught exception here, because that would roll back
                // this REQUIRES_NEW transaction and strand the row at PENDING forever (constraint
                // 2: a gateway failure must always reach a FAILED row plus a published event).
                String reason = result.failureReason() != null ? result.failureReason() : "GATEWAY_REJECTED";
                // Review fix (Important 2): pass the rail's own reference through even on a
                // decline, not a hardcoded null -- a rail can decline WITH a reference attached,
                // and discarding it broke the mobile-money webhook's ability to resolve a
                // REDELIVERED failure notification for that same reference later.
                requiresNewTransactionTemplate.executeWithoutResult(status ->
                    paymentApiImpl.failDisbursement(tenantId, disbursementId, result.gatewayReference(), reason));
            }
        } catch (GatewayException e) {
            // INDETERMINATE, not failed (review fix C2). A GatewayException means one of: a read
            // timeout (mobile-money.read-timeout-ms: 10000 -- the request was sent and the rail may
            // have paid before the socket went quiet), a 5xx, an empty body, or an ACCEPTED
            // response with no gatewayReference (MobileMoneyGatewayAdapter's own check). In none of
            // those cases do we know whether money moved -- which is exactly the consequence
            // judgment call 4 (no retry) predicted for this adapter and which was not carried into
            // the state machine until now.
            //
            // Note what is NOT here: the `else` branch above, an explicit rail DECLINE, still goes
            // to FAILED with a published event. That distinction is the whole fix -- "the rail said
            // no" and "the rail said nothing" are different facts, and only the first one justifies
            // policyloan's REVERSAL + releaseEncumbrance compensation.
            //
            // No gatewayReference is passed: there is no `result` at all when the rail never gave
            // us a usable response. That is exactly why C1's id-keyed callback resolver exists --
            // otherwise this row could never be resolved by any later notification.
            recordIndeterminate("disbursement", disbursementId, tenantId, e, () ->
                paymentApiImpl.markDisbursementInDoubt(tenantId, disbursementId, null));
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
                // Same defaulting as submitDisbursement's else-branch, same reason: a null here
                // would NPE inside Map.of(..., "reason", reason) and strand this row at PENDING.
                // Same Important-2 fix as submitDisbursement's else-branch: pass the rail's own
                // reference through on a decline, not a hardcoded null.
                String reason = result.failureReason() != null ? result.failureReason() : "GATEWAY_REJECTED";
                requiresNewTransactionTemplate.executeWithoutResult(status ->
                    paymentApiImpl.failCollection(tenantId, transactionId, result.gatewayReference(), reason));
            }
        } catch (GatewayException e) {
            // Same C2 routing as submitDisbursement's catch, same reasoning: indeterminate ->
            // IN_DOUBT with no published event; an explicit decline (the else-branch above) still
            // goes to FAILED with one.
            recordIndeterminate("collection", transactionId, tenantId, e, () ->
                paymentApiImpl.markCollectionInDoubt(tenantId, transactionId, null));
        }
    }

    /**
     * The alertable signal for an IN_DOUBT outcome (review fix C2). Because no {@code *Failed}
     * event is published for this state -- deliberately; see
     * {@code PaymentApiImpl.markDisbursementInDoubt}'s javadoc for why publishing one causes a
     * false financial compensation in policyloan -- the requesting module hears nothing at all, so
     * without this the state would be genuinely invisible outside the row itself. Two signals, both
     * required: an ERROR log naming the id an operator needs in order to reconcile against the
     * aggregator's statement, and a Micrometer counter
     * ({@value #IN_DOUBT_COUNTER}) that observability/alert-rules.yml alerts on.
     *
     * <p>The state transition runs in its own REQUIRES_NEW transaction, exactly as the FAILED and
     * COMPLETED transitions above do, and for the same reason (an AFTER_COMMIT listener cannot open
     * a transaction through a plain REQUIRED call -- see this class's own javadoc).
     */
    private void recordIndeterminate(String kind, UUID id, UUID tenantId, GatewayException cause, Runnable transition) {
        log.error("INDETERMINATE gateway outcome for {} {} (tenant {}): recorded as IN_DOUBT, NOT as FAILED -- "
            + "money may or may not have moved, so no *Failed event is published and no compensation is "
            + "triggered. Requires reconciliation against the aggregator's statement.", kind, id, tenantId, cause);
        meterRegistry.counter(IN_DOUBT_COUNTER, "kind", kind).increment();
        requiresNewTransactionTemplate.executeWithoutResult(status -> transition.run());
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
