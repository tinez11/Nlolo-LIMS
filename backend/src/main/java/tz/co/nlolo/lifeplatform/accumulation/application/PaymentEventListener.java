package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

/** Money that has actually moved: a withdrawal paid or failed, a top-up collected or failed. */
@Component("accumulationPaymentEventListener")
public class PaymentEventListener {

    private final AccumulationApiImpl api;
    private final Deposits deposits;
    private final EnvelopeRunner runner;

    public PaymentEventListener(AccumulationApiImpl api, Deposits deposits, EnvelopeRunner runner) {
        this.api = api;
        this.deposits = deposits;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "payment.DisbursementCompleted", "payment.DisbursementFailed" -> runner.run(envelope, p -> {
                boolean paid = "payment.DisbursementCompleted".equals(envelope.eventType());
                if ("DEPOSIT_MATURITY_PAYOUT".equals(p.get("purpose"))) {
                    deposits.settlePayout((String) p.get("sourceRef"), paid);
                    return;
                }
                if (!"WITHDRAWAL_PAYOUT".equals(p.get("purpose"))) return;
                api.settleWithdrawal(UUID.fromString((String) p.get("sourceRef")), (UUID) p.get("disbursementId"), paid);
            });
            case "payment.PaymentConfirmed" -> runner.run(envelope, p -> {
                if (!"ACCOUNT_TOP_UP".equals(p.get("purpose"))) return;
                @SuppressWarnings("unchecked")
                Map<String, Object> amount = (Map<String, Object>) p.get("amount");
                api.creditTopUp(UUID.fromString((String) p.get("sourceRef")),
                    new java.math.BigDecimal((String) amount.get("amount")),
                    LocalDate.ofInstant(Instant.parse((String) p.get("confirmedAt")), BillingEventListener.CIVIL_ZONE));
            });
            case "payment.PaymentFailed" -> runner.run(envelope, p -> {
                if (!"ACCOUNT_TOP_UP".equals(p.get("purpose"))) return;
                api.failTopUp(UUID.fromString((String) p.get("sourceRef")));
            });
            default -> { /* not ours */ }
        }
    }
}
