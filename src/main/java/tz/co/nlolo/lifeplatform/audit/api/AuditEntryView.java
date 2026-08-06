package tz.co.nlolo.lifeplatform.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditEntryView(UUID eventId, String eventType, Instant occurredAt, String payloadJson) {}
