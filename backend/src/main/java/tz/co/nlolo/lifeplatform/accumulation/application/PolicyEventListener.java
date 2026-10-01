package tz.co.nlolo.lifeplatform.accumulation.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.LocalDate;
import java.util.UUID;

/** The policy lifecycle, as it affects an account. Task 7 adds the closing events. */
// Explicit bean name: several modules declare a PolicyEventListener, and a duplicate default name
// fails application startup.
@Component("accumulationPolicyEventListener")
public class PolicyEventListener {

    private final AccumulationApiImpl api;
    private final EnvelopeRunner runner;

    public PolicyEventListener(AccumulationApiImpl api, EnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> runner.run(envelope, p -> api.openIfAccountVersion(
                (String) p.get("policyNumber"), (UUID) p.get("productVersionId"),
                LocalDate.parse((String) p.get("issueDate"))));
            default -> { /* not ours */ }
        }
    }
}
