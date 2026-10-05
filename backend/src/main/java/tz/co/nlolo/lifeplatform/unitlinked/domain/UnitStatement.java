package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A filed unit statement (U2, spec §6): the PDF lives in document; this row says which policy and period it covers and
 * whether it was the calendar-year one (one per policy and year, unitlinked V3's partial unique index) or on demand.
 * Written once, never changed: a statement asked for again is a new statement.
 */
@Entity(name = "UnitLinkedStatement")
@Table(name = "unit_statement", schema = "unitlinked")
public class UnitStatement {

    public enum Kind { ANNUAL, ON_DEMAND }

    @Id @Column(name = "statement_id") private UUID statementId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "period_from", nullable = false) private LocalDate periodFrom;
    @Column(name = "period_to", nullable = false) private LocalDate periodTo;
    @Column(name = "kind", nullable = false) private String kind;
    @Column(name = "document_ref", nullable = false) private String documentRef;
    @Column(name = "generated_by", nullable = false) private String generatedBy;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt;

    protected UnitStatement() {}

    public UnitStatement(UUID tenantId, String policyNumber, LocalDate periodFrom, LocalDate periodTo, Kind kind,
                         String documentRef, String generatedBy, Instant generatedAt) {
        this.statementId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.kind = kind.name();
        this.documentRef = documentRef;
        this.generatedBy = generatedBy;
        this.generatedAt = generatedAt;
    }

    public UUID getStatementId() { return statementId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public String getKind() { return kind; }
    public String getDocumentRef() { return documentRef; }
    public String getGeneratedBy() { return generatedBy; }
    public Instant getGeneratedAt() { return generatedAt; }
}
