package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.util.UUID;

/** An ACCOUNT version's terms (product step 3). Absent for a SCALE version -- see V19. */
@Entity
@Table(name = "version_accumulation_terms", schema = "product")
public class VersionAccumulationTerms {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "value_basis", nullable = false) private String valueBasis = "ACCOUNT";
    @Column(name = "guaranteed_rate_percent", nullable = false) private BigDecimal guaranteedRatePercent;
    @Column(name = "minimum_balance", nullable = false) private BigDecimal minimumBalance;

    protected VersionAccumulationTerms() {}

    public VersionAccumulationTerms(UUID tenantId, UUID productVersionId, BigDecimal guaranteedRatePercent,
                                    BigDecimal minimumBalance) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.guaranteedRatePercent = guaranteedRatePercent;
        this.minimumBalance = minimumBalance;
    }

    public BigDecimal getGuaranteedRatePercent() { return guaranteedRatePercent; }
    public BigDecimal getMinimumBalance() { return minimumBalance; }
}
