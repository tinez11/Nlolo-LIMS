package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.util.Map;

/** Policy's lifecycle, into the status record; a free-look cancellation also reverses every attachment. */
// Explicit bean name: several modules declare a PolicyEventListener, and a duplicate default name
// fails application startup.
@Component("bonusPolicyEventListener")
public class PolicyEventListener {

    private final StatusRecorder recorder;
    private final BonusEnvelopeRunner runner;
    private final BonusApiImpl api;

    public PolicyEventListener(StatusRecorder recorder, BonusEnvelopeRunner runner, BonusApiImpl api) {
        this.recorder = recorder;
        this.runner = runner;
        this.api = api;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if ("policy.PolicyCancelledFreeLook".equals(type)) {
            runner.run(envelope, e -> {
                recorder.record(e);
                @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) e.payload();
                api.reverseAllForFreeLook((String) p.get("policyNumber"), (String) p.get("cancelledBy"));
            });
            return;
        }
        if ("policy.PolicyIssued".equals(type) || StatusRecorder.STATUS_AFTER.containsKey(type)) {
            runner.run(envelope, recorder::record);
        }
    }
}
