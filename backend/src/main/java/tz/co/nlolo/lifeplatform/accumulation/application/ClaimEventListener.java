package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An approved DEATH claim closes the account as at the date of death. Consumed by envelope: the
 * accumulation module may not depend on claims, and needs nothing from it but this one fact.
 */
@Component("accumulationClaimEventListener")
public class ClaimEventListener {

    private final AccumulationApiImpl api;
    private final EnvelopeRunner runner;

    public ClaimEventListener(AccumulationApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"claims.ClaimApproved".equals(envelope.eventType())) {
            return;
        }
        runner.run(envelope, p -> {
            if (!"DEATH".equals(p.get("claimType"))) {
                return;
            }
            api.closeForDeath((String) p.get("policyNumber"), UUID.fromString(String.valueOf(p.get("claimId"))),
                LocalDate.parse((String) p.get("dateOfEvent")), "system:claims");
        });
    }
}
