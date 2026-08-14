package tz.co.nlolo.lifeplatform.distribution.domain;

import tz.co.nlolo.lifeplatform.distribution.api.DistributionValidationException;
import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Maps {@code distribution.commission_rule} 1:1 (db-migrations/distribution/V1:44-55). No
 * {@code version} or audit columns exist on this table.
 *
 * <p>The DB CHECK constraints added in V2 section 5 (rate XOR flat_amount, flat_amount paired
 * with flat_currency, both positive) are deliberately NOT re-validated here: only the
 * {@code THRESHOLD_BONUS} rejection below was requested for this task. Task 5's plan-authoring
 * path owns the rest of that validation, with the DB CHECK as the ultimate backstop.
 */
@Entity
@Table(name = "commission_rule", schema = "distribution")
public class CommissionRule {

    @Id
    @UuidGenerator
    @Column(name = "commission_rule_id")
    private UUID commissionRuleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "commission_plan_id", nullable = false)
    private UUID commissionPlanId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier_type", nullable = false)
    private TierType tierType;

    @Column
    private BigDecimal rate;

    @Column(name = "flat_amount")
    private BigDecimal flatAmount;

    @Column(name = "flat_currency")
    private String flatCurrency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "threshold_condition", columnDefinition = "jsonb")
    private String thresholdCondition;

    protected CommissionRule() {}

    /**
     * {@code tierType == THRESHOLD_BONUS} is rejected outright: the DB CHECK allows it (a rule
     * row could carry it) but nothing computes it (M7 user decision 1) -- see {@link TierType}'s
     * javadoc. This is deliberate scope-narrowing, not an oversight, so this guard must not be
     * relaxed as part of a later "just add the missing tier" change without a real design decision
     * to do so.
     */
    public CommissionRule(UUID tenantId, UUID commissionPlanId, TierType tierType, BigDecimal rate,
                           BigDecimal flatAmount, String flatCurrency, String thresholdCondition) {
        if (tierType == TierType.THRESHOLD_BONUS) {
            throw new DistributionValidationException(
                "THRESHOLD_BONUS is not computed by CommissionCalculator (M7 user decision 1); "
                    + "a commission rule cannot be authored with this tier type");
        }
        this.tenantId = tenantId;
        this.commissionPlanId = commissionPlanId;
        this.tierType = tierType;
        this.rate = rate;
        this.flatAmount = flatAmount;
        this.flatCurrency = flatCurrency;
        this.thresholdCondition = thresholdCondition;
    }

    public UUID getCommissionRuleId() { return commissionRuleId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getCommissionPlanId() { return commissionPlanId; }
    public TierType getTierType() { return tierType; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getFlatAmount() { return flatAmount; }
    public String getFlatCurrency() { return flatCurrency; }
    public String getThresholdCondition() { return thresholdCondition; }
}
