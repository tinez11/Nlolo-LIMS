package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A UNIT_LINKED version's U2 terms (V26): at most one row per version, and none on a version published before U2. */
@Entity
@Table(name = "unit_linked_options", schema = "product")
public class UnitLinkedOptionsEntity {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "free_switches_per_year") private Integer freeSwitchesPerYear;
    @Column(name = "switch_fee") private BigDecimal switchFee;
    @Column(name = "minimum_withdrawal") private BigDecimal minimumWithdrawal;
    @Column(name = "minimum_remaining_value") private BigDecimal minimumRemainingValue;
    @Column(name = "withdrawal_reduces_sum_assured", nullable = false) private boolean withdrawalReducesSumAssured;
    @Column(name = "top_up_allocation_percent") private BigDecimal topUpAllocationPercent;
    @Column(name = "minimum_top_up") private BigDecimal minimumTopUp;

    protected UnitLinkedOptionsEntity() {}

    public UnitLinkedOptionsEntity(UUID tenantId, UUID productVersionId, UnitLinkedOptions o) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.freeSwitchesPerYear = o.freeSwitchesPerYear();
        this.switchFee = o.switchFee();
        this.minimumWithdrawal = o.minimumWithdrawal();
        this.minimumRemainingValue = o.minimumRemainingValue();
        this.withdrawalReducesSumAssured = o.withdrawalReducesSumAssured();
        this.topUpAllocationPercent = o.topUpAllocationPercent();
        this.minimumTopUp = o.minimumTopUp();
    }

    public UnitLinkedOptions toOptions(List<UnitLinkedOptions.SurrenderChargeBand> surrenderCharges) {
        return new UnitLinkedOptions(freeSwitchesPerYear, switchFee, minimumWithdrawal, minimumRemainingValue,
            withdrawalReducesSumAssured, topUpAllocationPercent, minimumTopUp, surrenderCharges);
    }
}
