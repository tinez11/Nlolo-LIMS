package tz.co.nlolo.lifeplatform.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "failed_event", schema = "audit")
public class FailedEvent {

    @Id
    @GeneratedValue
    @Column(name = "failed_event_id")
    private UUID failedEventId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "consumer_module", nullable = false)
    private String consumerModule;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "first_failed_at", nullable = false)
    private Instant firstFailedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    protected FailedEvent() {}

    public FailedEvent(UUID tenantId, UUID eventId, String eventType, String consumerModule,
                        String failureReason, Instant firstFailedAt, String payload) {
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.consumerModule = consumerModule;
        this.failureReason = failureReason;
        this.retryCount = 0;
        this.firstFailedAt = firstFailedAt;
        this.payload = payload;
    }
}
