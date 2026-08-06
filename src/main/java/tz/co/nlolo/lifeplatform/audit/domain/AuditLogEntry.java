package tz.co.nlolo.lifeplatform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "audit_log", schema = "audit")
@IdClass(AuditLogEntry.AuditLogEntryId.class)
public class AuditLogEntry {

    @Id
    @Column(name = "audit_log_id")
    private UUID auditLogId;

    @Id
    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "sequence_number")
    private Long sequenceNumber;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected AuditLogEntry() {}

    public AuditLogEntry(UUID auditLogId, UUID tenantId, UUID eventId, String eventType, int schemaVersion,
                          Long sequenceNumber, Instant occurredAt, Instant recordedAt, String payload) {
        this.auditLogId = auditLogId;
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.sequenceNumber = sequenceNumber;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.payload = payload;
    }

    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getPayload() { return payload; }

    public static class AuditLogEntryId implements Serializable {
        private UUID auditLogId;
        private Instant occurredAt;

        public AuditLogEntryId() {}

        public AuditLogEntryId(UUID auditLogId, Instant occurredAt) {
            this.auditLogId = auditLogId;
            this.occurredAt = occurredAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof AuditLogEntryId that)) return false;
            return Objects.equals(auditLogId, that.auditLogId) && Objects.equals(occurredAt, that.occurredAt);
        }

        @Override
        public int hashCode() { return Objects.hash(auditLogId, occurredAt); }
    }
}
