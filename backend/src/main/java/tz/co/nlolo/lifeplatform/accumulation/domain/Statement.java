package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A filed statement and exactly what it was built from (spec §6). */
@Entity
@Table(name = "statement", schema = "accumulation")
public class Statement {
    @Id @UuidGenerator @Column(name = "statement_id") private UUID statementId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "period_from", nullable = false) private LocalDate periodFrom;
    @Column(name = "period_to", nullable = false) private LocalDate periodTo;
    @Column(name = "last_seq", nullable = false) private int lastSeq;
    @Column(name = "document_ref", nullable = false) private String documentRef;
    @Column(name = "generated_by", nullable = false) private String generatedBy;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt = Instant.now();

    protected Statement() {}

    public Statement(UUID tenantId, String policyNumber, LocalDate periodFrom, LocalDate periodTo, int lastSeq,
                     String documentRef, String generatedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.periodFrom = periodFrom;
        this.periodTo = periodTo;
        this.lastSeq = lastSeq;
        this.documentRef = documentRef;
        this.generatedBy = generatedBy;
    }

    public UUID getStatementId() { return statementId; }
    public String getPolicyNumber() { return policyNumber; }
    public LocalDate getPeriodFrom() { return periodFrom; }
    public LocalDate getPeriodTo() { return periodTo; }
    public int getLastSeq() { return lastSeq; }
    public String getDocumentRef() { return documentRef; }
    public String getGeneratedBy() { return generatedBy; }
    public Instant getGeneratedAt() { return generatedAt; }
}
