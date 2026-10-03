package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.bonus.api.BonusEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One movement in a policy's attached bonuses. Insert-only, no setters: the database refuses an update anyway. */
@Entity
@Table(name = "attachment_entry", schema = "bonus")
public class AttachmentEntry {
    @Id @UuidGenerator @Column(name = "entry_id") private UUID entryId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private int seq;
    @Column(name = "entry_type", nullable = false) private String entryType;
    @Column(nullable = false) private BigDecimal amount;
    @Column(name = "total_after", nullable = false) private BigDecimal totalAfter;
    @Column(name = "effective_date", nullable = false) private LocalDate effectiveDate;
    @Column(name = "declaration_id") private UUID declarationId;
    @Column(name = "basis_amount") private BigDecimal basisAmount;
    @Column(name = "rate_percent") private BigDecimal ratePercent;
    @Column(name = "source_type", nullable = false) private String sourceType;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Column(name = "reverses_entry_id") private UUID reversesEntryId;
    @Column private String reason;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected AttachmentEntry() {}

    public AttachmentEntry(UUID tenantId, String policyNumber, int seq, BonusEntryType type, BigDecimal amount,
                           BigDecimal totalAfter, LocalDate effectiveDate, UUID declarationId, BigDecimal basisAmount,
                           BigDecimal ratePercent, String sourceType, String sourceRef, UUID reversesEntryId,
                           String reason, String createdBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.seq = seq;
        this.entryType = type.name();
        this.amount = amount;
        this.totalAfter = totalAfter;
        this.effectiveDate = effectiveDate;
        this.declarationId = declarationId;
        this.basisAmount = basisAmount;
        this.ratePercent = ratePercent;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.reversesEntryId = reversesEntryId;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public UUID getEntryId() { return entryId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getSeq() { return seq; }
    public BonusEntryType type() { return BonusEntryType.valueOf(entryType); }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getTotalAfter() { return totalAfter; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public UUID getDeclarationId() { return declarationId; }
    public BigDecimal getBasisAmount() { return basisAmount; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public String getSourceRef() { return sourceRef; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
