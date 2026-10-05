package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.DependantClaimPayee;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;
import tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;
import tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule;

import java.util.List;
import java.util.UUID;

/** A FUNERAL version's claim rules and priced-age bound (V24). Absent for every other version. */
@Entity
@Table(name = "funeral_terms", schema = "product")
public class FuneralTermsEntity {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "max_priced_age", nullable = false) private int maxPricedAge;
    @Column(name = "waiting_period_months") private Integer waitingPeriodMonths;
    @Column(name = "accident_waives_waiting", nullable = false) private boolean accidentWaivesWaiting;
    @Column(name = "dependant_claim_payee", nullable = false) private String dependantClaimPayee;
    @Column(name = "on_main_member_death", nullable = false) private String onMainMemberDeath;
    @Column(name = "free_cover_to_paid_date", nullable = false) private boolean freeCoverToPaidDate;

    protected FuneralTermsEntity() {}

    public FuneralTermsEntity(UUID tenantId, UUID productVersionId, FuneralPlan plan) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.maxPricedAge = plan.maxPricedAge();
        this.waitingPeriodMonths = plan.waitingPeriodMonths();
        this.accidentWaivesWaiting = plan.accidentWaivesWaiting();
        this.dependantClaimPayee = plan.dependantClaimPayee().name();
        this.onMainMemberDeath = plan.onMainMemberDeath().name();
        this.freeCoverToPaidDate = plan.freeCoverToPaidDate();
    }

    public FuneralPlan toPlan(List<FuneralPlanOption> plans, List<FuneralPlanBenefit> benefits,
                              List<FuneralPremiumRow> premiums, List<FuneralRoleRule> roles) {
        return new FuneralPlan(true, plans, benefits, premiums, roles, maxPricedAge, waitingPeriodMonths,
            accidentWaivesWaiting, DependantClaimPayee.valueOf(dependantClaimPayee),
            MainMemberDeathRule.valueOf(onMainMemberDeath), freeCoverToPaidDate);
    }
}
