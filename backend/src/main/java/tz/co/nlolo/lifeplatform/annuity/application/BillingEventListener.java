package tz.co.nlolo.lifeplatform.annuity.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.util.Map;

/**
 * The single premium collected (product step 5): the moment an annuity's income locks. By envelope --
 * annuity may not depend on billing. onPremiumCollected asks product first, so every other policy's
 * premium passes straight through.
 */
@Component("annuityBillingEventListener")
public class BillingEventListener {

    private final AnnuityApiImpl api;
    private final AnnuityEnvelopeRunner runner;

    public BillingEventListener(AnnuityApiImpl api, AnnuityEnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        runner.run(envelope, e -> {
            Map<String, Object> p = Payloads.of(e.payload());
            api.onPremiumCollected((String) p.get("policyNumber"), Payloads.civilDate(p.get("collectedAt")));
        });
    }
}
