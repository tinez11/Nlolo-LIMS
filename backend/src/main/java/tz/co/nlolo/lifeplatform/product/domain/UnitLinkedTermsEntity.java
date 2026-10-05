package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** A UNIT_LINKED version's scalar terms (V25). Absent for every other version. */
@Entity
@Table(name = "unit_linked_terms", schema = "product")
public class UnitLinkedTermsEntity {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "monthly_policy_fee", nullable = false) private BigDecimal monthlyPolicyFee;
    @Column(name = "mortality_basis", nullable = false) private String mortalityBasis;
    @Column(name = "death_rule", nullable = false) private String deathRule;
    @Column(name = "lapse_rule", nullable = false) private String lapseRule;
    @Column(name = "minimum_premium_years") private Integer minimumPremiumYears;
    @Column(name = "minimum_surrender_years", nullable = false) private int minimumSurrenderYears;
    @Column(name = "low_fund_warning_months", nullable = false) private int lowFundWarningMonths;
    @Column(name = "sum_assured_multiple_min", nullable = false) private BigDecimal sumAssuredMultipleMin;
    @Column(name = "sum_assured_multiple_max", nullable = false) private BigDecimal sumAssuredMultipleMax;

    protected UnitLinkedTermsEntity() {}

    public UnitLinkedTermsEntity(UUID tenantId, UUID productVersionId, UnitLinkedPlan plan) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.monthlyPolicyFee = plan.monthlyPolicyFee();
        this.mortalityBasis = plan.mortalityBasis().name();
        this.deathRule = plan.deathRule().name();
        this.lapseRule = plan.lapseRule().name();
        this.minimumPremiumYears = plan.minimumPremiumYears();
        this.minimumSurrenderYears = plan.minimumSurrenderYears();
        this.lowFundWarningMonths = plan.lowFundWarningMonths();
        this.sumAssuredMultipleMin = plan.sumAssuredMultipleMin();
        this.sumAssuredMultipleMax = plan.sumAssuredMultipleMax();
    }

    public UnitLinkedPlan toPlan(List<String> fundCodes, List<UnitLinkedPlan.AllocationBand> bands,
                                 List<UnitLinkedPlan.MortalityRow> mortality, List<UnitLinkedPlan.PremiumMinimum> minimums) {
        return UnitLinkedPlan.of(fundCodes, bands, monthlyPolicyFee, UnitLinkedPlan.MortalityBasis.valueOf(mortalityBasis),
            mortality, UnitLinkedPlan.DeathRule.valueOf(deathRule), UnitLinkedPlan.LapseRule.valueOf(lapseRule),
            minimumPremiumYears, minimumSurrenderYears, lowFundWarningMonths, minimums, sumAssuredMultipleMin,
            sumAssuredMultipleMax);
    }
}
