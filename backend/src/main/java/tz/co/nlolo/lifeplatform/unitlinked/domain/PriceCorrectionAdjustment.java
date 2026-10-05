package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money a price correction moved on a payout already PAID (unitlinked V2; spec §3). It cannot be pulled back or
 * paid by the ledger itself, so it waits on a staff queue, never silently absorbed: owed to the customer, it is
 * paid out; owed by them, it is collected outside the platform or waived -- by someone other than whoever approved
 * the correction.
 */
@Entity
@Table(name = "price_correction_adjustment", schema = "unitlinked")
public class PriceCorrectionAdjustment {

    public enum Direction { OWED_TO_CUSTOMER, OWED_BY_CUSTOMER }

    @Id @Column(name = "adjustment_id") private UUID adjustmentId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "corrected_price_id", nullable = false) private UUID correctedPriceId;
    @Column(name = "amount", nullable = false) private BigDecimal amount;
    @Column(name = "direction", nullable = false) private String direction;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "proposed_by") private String proposedBy;
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "reason") private String reason;
    @Version @Column(name = "version", nullable = false) private long version;

    protected PriceCorrectionAdjustment() {}

    /** {@code difference} is what the corrected movement paid less what was paid: positive is owed to the customer. */
    public static PriceCorrectionAdjustment of(UUID tenantId, String policyNumber, UUID correctedPriceId,
                                               BigDecimal difference, String correctionApprovedBy) {
        PriceCorrectionAdjustment a = new PriceCorrectionAdjustment();
        a.adjustmentId = UUID.randomUUID();
        a.tenantId = tenantId;
        a.policyNumber = policyNumber;
        a.correctedPriceId = correctedPriceId;
        a.amount = difference.abs();
        a.direction = (difference.signum() > 0 ? Direction.OWED_TO_CUSTOMER : Direction.OWED_BY_CUSTOMER).name();
        a.status = "OPEN";
        a.proposedBy = correctionApprovedBy;
        return a;
    }

    public void settle(String by, String reference, Instant now) {
        decide(by, now);
        this.status = "SETTLED";
        this.reason = reference;
    }

    public void waive(String by, String reason, Instant now) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Waiving a price-correction adjustment needs a reason");
        }
        decide(by, now);
        this.status = "WAIVED";
        this.reason = reason.trim();
    }

    private void decide(String by, Instant now) {
        if (!"OPEN".equals(status)) {
            throw new UnitLinkedStateException("Adjustment " + adjustmentId + " is already " + status);
        }
        if (by.equals(proposedBy)) {
            throw new UnitLinkedStateException("Adjustment " + adjustmentId + " arose from a correction " + proposedBy
                + " approved; a second person settles or waives it");
        }
        this.decidedBy = by;
        this.decidedAt = now;
    }

    public UUID getAdjustmentId() { return adjustmentId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getCorrectedPriceId() { return correctedPriceId; }
    public BigDecimal getAmount() { return amount; }
    public Direction getDirection() { return Direction.valueOf(direction); }
    public String getStatus() { return status; }
    public String getProposedBy() { return proposedBy; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getReason() { return reason; }
}
