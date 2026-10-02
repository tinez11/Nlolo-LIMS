package tz.co.nlolo.lifeplatform.bonus.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.bonus.api.ExitType;

import java.time.LocalDate;
import java.util.Map;

/** An approved DEATH claim records the bonus it was valued with. By envelope: bonus may not depend on claims. */
@Component("bonusClaimEventListener")
public class ClaimEventListener {

    private final BonusApiImpl api;
    private final BonusEnvelopeRunner runner;

    public ClaimEventListener(BonusApiImpl api, BonusEnvelopeRunner runner) {
        this.api = api;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"claims.ClaimApproved".equals(envelope.eventType())) {
            return;
        }
        runner.run(envelope, e -> {
            @SuppressWarnings("unchecked") Map<String, Object> p = (Map<String, Object>) e.payload();
            if (!"DEATH".equals(p.get("claimType"))) {
                return;
            }
            // settle() asks the gate first, so a claim on an ordinary policy reads no bonus table.
            api.settle((String) p.get("policyNumber"), ExitType.DEATH, String.valueOf(p.get("claimId")),
                LocalDate.parse((String) p.get("dateOfEvent")));
        });
    }
}
