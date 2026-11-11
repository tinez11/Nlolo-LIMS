package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code billing.PremiumCollected} on a unit-linked policy buys units (spec §5). Billing publishes it only for an
 * invoice settled -- never a top-up, which billing's own payment listener routes elsewhere -- so no purpose filter
 * is needed (checked 2026-10-05). Every other policy's premium is ignored at once: no allocation, no work.
 */
@Component("unitLinkedBillingEventListener")
class BillingEventListener {

    private final Allocations allocations;
    private final Exits exits;
    private final UnitLinkedEnvelopeRunner runner;

    BillingEventListener(Allocations allocations, Exits exits, UnitLinkedEnvelopeRunner runner) {
        this.allocations = allocations;
        this.exits = exits;
        this.runner = runner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        // Agent cash (2026-11-11, the user): the premium is paid -- cover on, no dunning -- the moment the agent records
        // it, but units are bought only when the money reaches the company. Undone after 14 days, nothing was invested.
        if ("billing.AgentCashBanked".equals(envelope.eventType())) {
            runner.run(envelope, p -> {
                String policyNumber = (String) p.get("policyNumber");
                if (!exits.isUnitLinked(policyNumber)) {
                    return;
                }
                Instant bankedAt = Instant.parse((String) p.get("bankedAt"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> paid = (List<Map<String, Object>>) p.getOrDefault("premiumsPaid", List.of());
                for (Map<String, Object> premium : paid) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> amount = (Map<String, Object>) premium.get("amount");
                    allocations.onPremiumCollected(policyNumber, UUID.fromString(String.valueOf(premium.get("invoiceId"))),
                        new BigDecimal(String.valueOf(amount.get("amount"))), bankedAt);
                }
            });
            return;
        }
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        if (envelope.payload() instanceof Map<?, ?> m && "AGENT_CASH".equals(m.get("receiptMethod"))) {
            return; // bought on billing.AgentCashBanked, above
        }
        runner.run(envelope, p -> {
            String policyNumber = (String) p.get("policyNumber");
            if (!exits.isUnitLinked(policyNumber)) {
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> amount = (Map<String, Object>) p.get("amount");
            allocations.onPremiumCollected(policyNumber,
                // A UUID in process, a String after any serialising hop -- accept both.
                UUID.fromString(String.valueOf(p.get("invoiceId"))),
                new BigDecimal(String.valueOf(amount.get("amount"))),
                Instant.parse((String) p.get("collectedAt")));
        });
    }
}
