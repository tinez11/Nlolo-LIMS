package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.AccumulationChargeRow;

import java.math.BigDecimal;
import java.util.UUID;

/** One run of policy years' charges on an ACCOUNT version (product step 3). */
@Entity
@Table(name = "accumulation_charge", schema = "product")
public class AccumulationCharge {
    @Id @UuidGenerator @Column(name = "accumulation_charge_id") private UUID accumulationChargeId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "from_policy_year", nullable = false) private int fromPolicyYear;
    @Column(name = "to_policy_year") private Integer toPolicyYear;
    @Column(name = "contribution_allocation_percent", nullable = false) private BigDecimal contributionAllocationPercent;
    @Column(name = "transfer_allocation_percent", nullable = false) private BigDecimal transferAllocationPercent;
    @Column(name = "monthly_policy_fee", nullable = false) private BigDecimal monthlyPolicyFee;

    protected AccumulationCharge() {}

    public AccumulationCharge(UUID tenantId, UUID productVersionId, AccumulationChargeRow row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fromPolicyYear = row.fromPolicyYear();
        this.toPolicyYear = row.toPolicyYear();
        this.contributionAllocationPercent = row.contributionAllocationPercent();
        this.transferAllocationPercent = row.transferAllocationPercent();
        this.monthlyPolicyFee = row.monthlyPolicyFee();
    }

    public AccumulationChargeRow toRow() {
        return new AccumulationChargeRow(fromPolicyYear, toPolicyYear, contributionAllocationPercent,
            transferAllocationPercent, monthlyPolicyFee);
    }
}
