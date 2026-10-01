package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.PayoutTerms;

import java.math.BigDecimal;
import java.util.UUID;

/** A version's free-look and payout servicing terms (product step 2). One row per version. */
@Entity
@Table(name = "version_payout_terms", schema = "product")
public class VersionPayoutTerms {

    @Id
    @Column(name = "product_version_id")
    private UUID productVersionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "free_look_days")
    private Integer freeLookDays;

    @Column(name = "proof_of_life_interval_months")
    private Integer proofOfLifeIntervalMonths;

    @Column(name = "survival_benefits_deducted_from_death")
    private Boolean survivalBenefitsDeductedFromDeath;

    @Column(name = "death_benefit_premium_percent")
    private BigDecimal deathBenefitPremiumPercent;

    protected VersionPayoutTerms() {}

    public VersionPayoutTerms(UUID tenantId, UUID productVersionId, PayoutTerms t) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.freeLookDays = t.freeLookDays();
        this.proofOfLifeIntervalMonths = t.proofOfLifeIntervalMonths();
        this.survivalBenefitsDeductedFromDeath = t.survivalBenefitsDeductedFromDeath();
        this.deathBenefitPremiumPercent = t.deathBenefitPremiumPercent();
    }

    public PayoutTerms toTerms() {
        return new PayoutTerms(freeLookDays, proofOfLifeIntervalMonths, survivalBenefitsDeductedFromDeath,
            deathBenefitPremiumPercent);
    }
}
