package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What the ledger carries for one fund's units after the last pricing run (unitlinked V2, plan D1): exactly
 * {@code unitsInIssue × price}, rounded once. The next run's revaluation is the true-up to the new figure.
 */
@Entity
@Table(name = "fund_liability", schema = "unitlinked")
public class FundLiability {
    @Id @Column(name = "fund_id") private UUID fundId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "carried", nullable = false) private BigDecimal carried;
    @Column(name = "price_id", nullable = false) private UUID priceId;
    @Column(name = "units_in_issue", nullable = false) private BigDecimal unitsInIssue;
    @Version @Column(name = "version", nullable = false) private long version;

    protected FundLiability() {}

    public FundLiability(UUID tenantId, UUID fundId) {
        this.tenantId = tenantId;
        this.fundId = fundId;
        this.carried = BigDecimal.ZERO.setScale(2);
        this.unitsInIssue = BigDecimal.ZERO.setScale(6);
    }

    public void carry(BigDecimal carried, UUID priceId, BigDecimal unitsInIssue) {
        this.carried = carried;
        this.priceId = priceId;
        this.unitsInIssue = unitsInIssue;
    }

    /**
     * Value moved into (positive) or out of the fund inside 2150 without a pricing run -- a switch's legs (U2, plan D5).
     * The next true-up measures from this figure, so 2150 stays units x price for every fund.
     */
    public void adjust(BigDecimal by) {
        this.carried = carried.add(by);
    }

    public UUID getFundId() { return fundId; }
    public BigDecimal getCarried() { return carried; }
    public UUID getPriceId() { return priceId; }
    public BigDecimal getUnitsInIssue() { return unitsInIssue; }
}
