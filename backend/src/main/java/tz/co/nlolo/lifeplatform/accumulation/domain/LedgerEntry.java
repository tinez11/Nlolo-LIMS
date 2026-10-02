package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.EntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One financial transaction. No setters, {@code @Immutable}, and the database refuses edits besides. */
@Entity
@Immutable
@Table(name = "ledger_entry", schema = "accumulation")
public class LedgerEntry {
    @Id @UuidGenerator @Column(name = "entry_id") private UUID entryId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "posting_id", nullable = false) private UUID postingId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private int seq;
    @Column(name = "entry_type", nullable = false) private String entryType;
    @Column(nullable = false) private BigDecimal amount;
    @Column(name = "balance_after", nullable = false) private BigDecimal balanceAfter;
    @Column(name = "effective_date", nullable = false) private LocalDate effectiveDate;
    @Column(name = "posted_at", nullable = false) private Instant postedAt = Instant.now();
    @Column(name = "reverses_entry_id") private UUID reversesEntryId;
    @Column private String reason;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "approved_by") private String approvedBy;

    protected LedgerEntry() {}

    public LedgerEntry(UUID tenantId, UUID postingId, String policyNumber, int seq, EntryType type,
                       BigDecimal amount, BigDecimal balanceAfter, LocalDate effectiveDate,
                       UUID reversesEntryId, String reason, String createdBy, String approvedBy) {
        this.tenantId = tenantId;
        this.postingId = postingId;
        this.policyNumber = policyNumber;
        this.seq = seq;
        this.entryType = type.name();
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.effectiveDate = effectiveDate;
        this.reversesEntryId = reversesEntryId;
        this.reason = reason;
        this.createdBy = createdBy;
        this.approvedBy = approvedBy;
    }

    public UUID getEntryId() { return entryId; }
    public UUID getPostingId() { return postingId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getSeq() { return seq; }
    public EntryType type() { return EntryType.valueOf(entryType); }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public Instant getPostedAt() { return postedAt; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public String getReason() { return reason; }
    public String getCreatedBy() { return createdBy; }
    public String getApprovedBy() { return approvedBy; }
}
