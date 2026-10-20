package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * One return, amendment or resubmission on a case (2026-10-20, underwriting V23). Append-only: nothing updates or
 * deletes one, because the round-by-round record is what the underwriter reads to see what changed.
 */
@Entity
@Table(name = "case_event", schema = "underwriting")
public class CaseEvent {

    public enum Kind { RETURNED, AMENDED, RESUBMITTED }

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "round", nullable = false, updatable = false)
    private int round;

    @Column(name = "actor_subject", nullable = false, updatable = false)
    private String actorSubject;

    @Column(name = "reasons", columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String reasons;

    @Column(name = "changes", columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String changes;

    @Column(name = "previous_decision", columnDefinition = "jsonb", updatable = false)
    @JdbcTypeCode(SqlTypes.JSON)
    private String previousDecision;

    @Column(name = "note", updatable = false)
    private String note;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected CaseEvent() {}

    public CaseEvent(UUID tenantId, UUID caseId, Kind kind, int round, String actorSubject, String reasons,
                     String changes, String previousDecision, String note) {
        this.eventId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.kind = kind.name();
        this.round = round;
        this.actorSubject = actorSubject;
        this.reasons = reasons;
        this.changes = changes;
        this.previousDecision = previousDecision;
        this.note = note;
    }

    public UUID getEventId() { return eventId; }
    public UUID getCaseId() { return caseId; }
    public Kind getKind() { return Kind.valueOf(kind); }
    public int getRound() { return round; }
    public String getActorSubject() { return actorSubject; }
    public String getReasons() { return reasons; }
    public String getChanges() { return changes; }
    public String getPreviousDecision() { return previousDecision; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
