package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One change in a participating policy's status (V1). Insert-only; the database refuses edits. */
@Entity
@Table(name = "status_event", schema = "bonus")
public class StatusEvent {
    @Id @UuidGenerator @Column(name = "status_event_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "event_id", nullable = false) private UUID eventId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String status;
    @Column(name = "sum_assured") private BigDecimal sumAssured;
    @Column(name = "effective_at", nullable = false) private Instant effectiveAt;

    protected StatusEvent() {}

    public StatusEvent(UUID tenantId, UUID eventId, String policyNumber, String status, BigDecimal sumAssured, Instant effectiveAt) {
        this.tenantId = tenantId;
        this.eventId = eventId;
        this.policyNumber = policyNumber;
        this.status = status;
        this.sumAssured = sumAssured;
        this.effectiveAt = effectiveAt;
    }

    public Eligibility.StatusRow toRow() { return new Eligibility.StatusRow(status, sumAssured, effectiveAt); }
    public String getStatus() { return status; }
    public BigDecimal getSumAssured() { return sumAssured; }
    public Instant getEffectiveAt() { return effectiveAt; }
}
