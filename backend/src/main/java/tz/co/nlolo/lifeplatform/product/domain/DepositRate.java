package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.DepositRateRow;

import java.math.BigDecimal;
import java.util.UUID;

/** One cell of a deposit version's grid (V20). */
@Entity
@Table(name = "deposit_rate_row", schema = "product")
public class DepositRate {
    @Id @UuidGenerator @Column(name = "deposit_rate_row_id") private UUID depositRateRowId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "min_amount", nullable = false) private BigDecimal minAmount;
    @Column(name = "term_months", nullable = false) private int termMonths;
    @Column(name = "rate_percent", nullable = false) private BigDecimal ratePercent;

    protected DepositRate() {}

    public DepositRate(UUID tenantId, UUID productVersionId, DepositRateRow row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.minAmount = row.minAmount();
        this.termMonths = row.termMonths();
        this.ratePercent = row.ratePercent();
    }

    public DepositRateRow toRow() { return new DepositRateRow(minAmount, termMonths, ratePercent); }
}
