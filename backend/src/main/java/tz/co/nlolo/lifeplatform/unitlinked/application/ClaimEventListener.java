package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A death on a unit-linked policy (spec §7). Registration freezes it and sells its units at the first price after
 * the REGISTRATION instant -- read from the event (plan C4), never the time this listener happens to run, which is
 * later and could meet a price approved in between. Approval records the cost-of-insurance refund; rejection puts
 * the money back into units.
 */
@Component("unitLinkedClaimEventListener")
class ClaimEventListener {

    private final Exits exits;
    private final UnitLinkedEnvelopeRunner runner;
    private final Clock clock;

    ClaimEventListener(Exits exits, UnitLinkedEnvelopeRunner runner, @Qualifier("unitLinkedClock") Clock clock) {
        this.exits = exits;
        this.runner = runner;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if (!"claims.ClaimRegistered".equals(type) && !"claims.ClaimApproved".equals(type)
                && !"claims.ClaimRejected".equals(type)) {
            return;
        }
        runner.run(envelope, p -> {
            String policyNumber = (String) p.get("policyNumber");
            if (!"DEATH".equals(p.get("claimType")) || policyNumber == null || !exits.isUnitLinked(policyNumber)) {
                return;
            }
            UUID claimId = UUID.fromString(String.valueOf(p.get("claimId")));
            switch (type) {
                case "claims.ClaimRegistered" -> {
                    Object at = p.get("registeredAt");
                    exits.onDeathRegistered(claimId, policyNumber, at != null ? Instant.parse(String.valueOf(at)) : clock.instant());
                }
                case "claims.ClaimApproved" -> exits.onDeathApproved(claimId, LocalDate.parse(String.valueOf(p.get("dateOfEvent"))));
                default -> exits.onDeathRejected(claimId, clock.instant());
            }
        });
    }
}
