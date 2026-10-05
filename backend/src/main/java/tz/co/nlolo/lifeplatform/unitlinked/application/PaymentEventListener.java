package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.math.BigDecimal;
import java.util.Map;

/**
 * payment's outcome for a payout this module asked for (surrender, maturity, lapse, a price-correction adjustment): a
 * completed payment marks the exit paid -- which a later price correction reads to raise an adjustment rather than
 * move money -- closes a matured policy, and publishes {@code unitlinked.PayoutPaid}, the cash leg finaccounting books
 * (no platform rule books these purposes otherwise). A failed one leaves the exit priced; finance retries on payment's
 * own path.
 */
@Component("unitLinkedPaymentEventListener")
class PaymentEventListener {

    private final Exits exits;
    private final Adjustments adjustments;
    private final UnitLinkedEnvelopeRunner runner;

    PaymentEventListener(Exits exits, Adjustments adjustments, UnitLinkedEnvelopeRunner runner) {
        this.exits = exits;
        this.adjustments = adjustments;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"payment.DisbursementCompleted".equals(envelope.eventType())) {
            return;
        }
        // Only this module's own payouts, recognised by their key, before any table is read.
        Object key = ((Map<?, ?>) envelope.payload()).get("idempotencyKey");
        String k = key == null ? "" : String.valueOf(key);
        if (!k.startsWith("unit-linked:") && !k.startsWith(Adjustments.KEY_PREFIX)) {
            return;
        }
        runner.run(envelope, p -> {
            Object purpose = p.get("purpose");
            Object sourceRef = p.get("sourceRef");
            Object disbursementId = p.get("disbursementId");
            if (purpose == null || sourceRef == null || disbursementId == null) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> money = (Map<String, Object>) p.get("amount");
            Paid paid = new Paid(String.valueOf(purpose), String.valueOf(sourceRef), String.valueOf(disbursementId),
                new BigDecimal(String.valueOf(money.get("amount"))), String.valueOf(money.get("currencyCode")));
            if (k.startsWith(Adjustments.KEY_PREFIX)) {
                adjustments.onPaid(paid);
            } else {
                exits.onPaid(paid);
            }
        });
    }

    /** One completed disbursement of this module's. */
    record Paid(String purpose, String sourceRef, String disbursementId, BigDecimal amount, String currency) {

        Map<String, Object> payload(String policyNumber) {
            return Map.of("purpose", purpose, "sourceRef", disbursementId, "payoutRef", sourceRef,
                "policyNumber", policyNumber, "amount", amount.toPlainString(), "currencyCode", currency);
        }
    }
}
