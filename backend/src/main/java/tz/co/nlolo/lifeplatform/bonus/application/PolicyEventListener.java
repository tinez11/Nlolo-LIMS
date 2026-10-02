package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

/** Policy's lifecycle, into the status record. Task 6 adds the free-look reversal. */
// Explicit bean name: several modules declare a PolicyEventListener, and a duplicate default name
// fails application startup.
@Component("bonusPolicyEventListener")
public class PolicyEventListener {

    private final StatusRecorder recorder;
    private final BonusEnvelopeRunner runner;

    public PolicyEventListener(StatusRecorder recorder, BonusEnvelopeRunner runner) {
        this.recorder = recorder;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if ("policy.PolicyIssued".equals(type) || StatusRecorder.STATUS_AFTER.containsKey(type)) {
            runner.run(envelope, recorder::record);
        }
    }
}
