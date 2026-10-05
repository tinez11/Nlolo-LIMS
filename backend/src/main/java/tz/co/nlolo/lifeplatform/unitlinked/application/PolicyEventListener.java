package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Policy's events that move units: an issued policy's fund split is written; an approved surrender sells forward
 * from its APPROVAL instant; an arrears lapse sells forward and pays a lapse surrender value; a free-look
 * cancellation unwinds the policy's entries. A lapse this module caused itself (FUND_EXHAUSTED) is not acted on again.
 */
@Component("unitLinkedPolicyEventListener")
class PolicyEventListener {

    private final Allocations allocations;
    private final Exits exits;
    private final UnitLinkedEnvelopeRunner runner;
    private final Clock clock;

    PolicyEventListener(Allocations allocations, Exits exits, UnitLinkedEnvelopeRunner runner,
                        @Qualifier("unitLinkedClock") Clock clock) {
        this.allocations = allocations;
        this.exits = exits;
        this.runner = runner;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> runner.run(envelope, p -> allocations.recordAtIssue((String) p.get("policyNumber")));
            case "policy.UnitLinkedSurrenderApproved" -> runner.run(envelope, p -> exits.onSurrenderApproved(
                UUID.fromString(String.valueOf(p.get("surrenderRequestId"))), (String) p.get("policyNumber"),
                (String) p.get("payeeRef"), Instant.parse(String.valueOf(p.get("approvedAt")))));
            case "policy.PolicyLapsed" -> runner.run(envelope, p -> {
                String policyNumber = (String) p.get("policyNumber");
                // An arrears lapse carries no reason; an exhaustion lapse is this module's own doing.
                if (p.get("reason") != null || !exits.isUnitLinked(policyNumber)) {
                    return;
                }
                Object at = p.get("lapsedAt");
                exits.lapse(policyNumber, at != null ? Instant.parse(String.valueOf(at)) : clock.instant());
            });
            case "policy.PolicyCancelledFreeLook" -> runner.run(envelope, p -> {
                String policyNumber = (String) p.get("policyNumber");
                if (!exits.isUnitLinked(policyNumber)) {
                    return;
                }
                Object at = p.get("cancelledAt");
                exits.onFreeLookCancelled(policyNumber, at != null ? Instant.parse(String.valueOf(at)) : clock.instant());
            });
            default -> { /* not ours */ }
        }
    }
}
