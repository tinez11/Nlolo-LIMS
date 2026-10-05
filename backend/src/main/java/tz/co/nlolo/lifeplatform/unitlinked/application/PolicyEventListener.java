package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

/**
 * Policy's events that move units. Task 3: {@code policy.PolicyIssued} writes a unit-linked policy's fund split
 * from its case. Later tasks add surrender, lapse, maturity and free-look here.
 */
@Component("unitLinkedPolicyEventListener")
class PolicyEventListener {

    private final Allocations allocations;
    private final UnitLinkedEnvelopeRunner runner;

    PolicyEventListener(Allocations allocations, UnitLinkedEnvelopeRunner runner) {
        this.allocations = allocations;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if ("policy.PolicyIssued".equals(envelope.eventType())) {
            runner.run(envelope, p -> allocations.recordAtIssue((String) p.get("policyNumber")));
        }
    }
}
