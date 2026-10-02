package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The one way a PERSON corrects a ledger (spec §4.1): PROPOSED -> APPROVED | REJECTED, by a second
 * person. A REVERSAL is the system undoing its own entry; this is a signed amount with a reason,
 * posted as an ADJUSTMENT entry only on approval. The database's ledger_entry_adjustment_approved
 * CHECK is the other half of the same rule.
 */
@Entity
@Table(name = "adjustment_request", schema = "accumulation")
public class AdjustmentRequest {
    @Id @UuidGenerator @Column(name = "adjustment_id") private UUID adjustmentId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String reason;
    @Column(nullable = false) private String status = "PROPOSED";
    @Column(name = "proposed_by", nullable = false) private String proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt = Instant.now();
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Version private long version;

    protected AdjustmentRequest() {}

    public AdjustmentRequest(UUID tenantId, String policyNumber, BigDecimal amount, String reason, String proposedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.reason = reason;
        this.proposedBy = proposedBy;
    }

    public void approve(String by) { decide(by, "APPROVED"); }

    public void reject(String by) { decide(by, "REJECTED"); }

    private void decide(String by, String outcome) {
        if (!"PROPOSED".equals(status)) {
            throw new AccumulationStateException("This adjustment is " + status.toLowerCase() + ", not awaiting a decision");
        }
        if (by.equals(proposedBy)) {
            throw new AccumulationStateException("An adjustment must be decided by someone other than the person who proposed it");
        }
        this.status = outcome;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
    }

    public UUID getAdjustmentId() { return adjustmentId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
