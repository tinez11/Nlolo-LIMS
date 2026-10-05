package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One fund's price for one valuation date (unitlinked V1): PROPOSED by one person, APPROVED by a second, never
 * before the date's cut-off. Once approved its figures never change (a trigger enforces it); a mistake is
 * corrected by a new price that SUPERSEDES it.
 */
@Entity
@Table(name = "fund_price", schema = "unitlinked")
public class FundPrice {

    @Id @Column(name = "price_id") private UUID priceId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "fund_id", nullable = false) private UUID fundId;
    @Column(name = "valuation_date", nullable = false) private LocalDate valuationDate;
    @Column(name = "price", nullable = false) private BigDecimal price;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "move_reason") private String moveReason;
    @Column(name = "supersedes_price_id") private UUID supersedesPriceId;
    @Column(name = "proposed_by", nullable = false) private String proposedBy;
    @Column(name = "proposed_at", nullable = false) private Instant proposedAt;
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Version @Column(name = "version", nullable = false) private long version;

    protected FundPrice() {}

    public static FundPrice propose(UUID tenantId, UUID fundId, LocalDate valuationDate, BigDecimal price,
                                    String moveReason, UUID supersedesPriceId, String proposedBy, Instant now) {
        if (price == null || price.signum() <= 0) {
            throw new IllegalArgumentException("A fund price must be greater than zero");
        }
        if (price.stripTrailingZeros().scale() > 6) {
            throw new IllegalArgumentException("A fund price has at most 6 decimal places");
        }
        FundPrice p = new FundPrice();
        p.priceId = UUID.randomUUID();
        p.tenantId = tenantId;
        p.fundId = fundId;
        p.valuationDate = valuationDate;
        p.price = price.setScale(6);
        p.status = "PROPOSED";
        p.moveReason = moveReason == null || moveReason.isBlank() ? null : moveReason.trim();
        p.supersedesPriceId = supersedesPriceId;
        p.proposedBy = proposedBy;
        p.proposedAt = now;
        return p;
    }

    /** By a second person, at or after the valuation date's cut-off (BindingRule.earliestApproval). */
    public void approve(String by, Instant now, String fundCode, LocalTime cutOff) {
        if (!"PROPOSED".equals(status)) {
            throw new UnitLinkedStateException("The price of " + fundCode + " for " + valuationDate + " is " + status
                + "; only a proposed price can be approved");
        }
        if (by.equals(proposedBy)) {
            throw new UnitLinkedStateException("The price of " + fundCode + " for " + valuationDate + " was proposed by "
                + proposedBy + "; a second person approves it");
        }
        Instant earliest = BindingRule.earliestApproval(valuationDate, cutOff);
        if (now.isBefore(earliest)) {
            throw new UnitLinkedStateException("The price of " + fundCode + " for " + valuationDate
                + " cannot be approved before its " + cutOff + " cut-off: money for that date may still arrive");
        }
        this.status = "APPROVED";
        this.approvedBy = by;
        this.approvedAt = now;
    }

    public void supersede() {
        if (!"APPROVED".equals(status)) {
            throw new UnitLinkedStateException("Only an approved price can be superseded (price " + priceId + " is " + status + ")");
        }
        this.status = "SUPERSEDED";
    }

    /** A proposal its proposer takes back, before anyone approves it. */
    public void withdraw(String by, String fundCode) {
        if (!"PROPOSED".equals(status)) {
            throw new UnitLinkedStateException("The price of " + fundCode + " for " + valuationDate + " is " + status
                + "; only a proposed price can be withdrawn");
        }
        if (!by.equals(proposedBy)) {
            throw new UnitLinkedStateException("Only " + proposedBy + ", who proposed it, can withdraw this price");
        }
        this.status = "WITHDRAWN";
    }

    public UUID getPriceId() { return priceId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getFundId() { return fundId; }
    public LocalDate getValuationDate() { return valuationDate; }
    public BigDecimal getPrice() { return price; }
    public String getStatus() { return status; }
    public String getMoveReason() { return moveReason; }
    public UUID getSupersedesPriceId() { return supersedesPriceId; }
    public String getProposedBy() { return proposedBy; }
    public Instant getProposedAt() { return proposedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
}
