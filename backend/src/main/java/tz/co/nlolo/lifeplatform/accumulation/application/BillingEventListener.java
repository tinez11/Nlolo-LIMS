package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

@Component("accumulationBillingEventListener")
public class BillingEventListener {

    /**
     * The civil day a collection belongs to. Private to each module, as policy's and communication's
     * CIVIL_ZONE are; package-private here because the payment and policy listeners date by it too.
     */
    static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final AccumulationApiImpl api;
    private final EnvelopeRunner runner;

    public BillingEventListener(AccumulationApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        runner.run(envelope, p -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> amount = (Map<String, Object>) p.get("amount");
            api.creditContribution(
                (String) p.get("policyNumber"),
                // A UUID in process, a String after any serialising hop -- accept both.
                UUID.fromString(String.valueOf(p.get("invoiceId"))),
                new BigDecimal((String) amount.get("amount")),
                LocalDate.ofInstant(Instant.parse((String) p.get("collectedAt")), CIVIL_ZONE),
                // Absent for a field (cash) receipt: no number to pay a deposit back to.
                (String) p.get("payerRef"));
        });
    }
}
