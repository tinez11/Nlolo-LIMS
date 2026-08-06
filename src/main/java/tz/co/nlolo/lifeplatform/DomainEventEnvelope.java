package tz.co.nlolo.lifeplatform;

import java.time.Instant;
import java.util.UUID;

/**
 * Shared event envelope every module publishes and audit.DomainEventAuditListener
 * consumes generically (docs/02-module-architecture.md §2, docs/05-event-catalog.md
 * §1's EventEnvelopeMeta). sequenceNumber is nullable -- only order-sensitive async
 * consumers need it (docs/05-event-catalog.md §5); none exist yet at M1.
 */
public record DomainEventEnvelope<T>(
    UUID eventId,
    String eventType,
    int schemaVersion,
    UUID tenantId,
    Instant occurredAt,
    Long sequenceNumber,
    T payload
) {
    public static <T> DomainEventEnvelope<T> of(String eventType, UUID tenantId, T payload) {
        return new DomainEventEnvelope<>(UUID.randomUUID(), eventType, 1, tenantId, Instant.now(), null, payload);
    }
}
