package tz.co.nlolo.lifeplatform.product.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;
import tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;
import tz.co.nlolo.lifeplatform.product.domain.FuneralPlanBenefitEntity;
import tz.co.nlolo.lifeplatform.product.domain.FuneralPlanEntity;
import tz.co.nlolo.lifeplatform.product.domain.FuneralPremiumEntity;
import tz.co.nlolo.lifeplatform.product.domain.FuneralRoleRuleEntity;
import tz.co.nlolo.lifeplatform.product.domain.FuneralTermsEntity;
import tz.co.nlolo.lifeplatform.product.infrastructure.FuneralPlanBenefitRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.FuneralPlanRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.FuneralPremiumRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.FuneralRoleRuleRepository;
import tz.co.nlolo.lifeplatform.product.infrastructure.FuneralTermsRepository;

import java.util.UUID;

/**
 * A FUNERAL version's five tables (V24), written and read as one. Its own component so ProductApiImpl's
 * constructor does not grow by five more repositories; called only from inside ProductApiImpl's
 * transactions, and only after the category says FUNERAL.
 */
@Component
class FuneralTermsStore {

    private final FuneralTermsRepository terms;
    private final FuneralPlanRepository plans;
    private final FuneralPlanBenefitRepository benefits;
    private final FuneralPremiumRepository premiums;
    private final FuneralRoleRuleRepository roles;

    FuneralTermsStore(FuneralTermsRepository terms, FuneralPlanRepository plans, FuneralPlanBenefitRepository benefits,
                      FuneralPremiumRepository premiums, FuneralRoleRuleRepository roles) {
        this.terms = terms;
        this.plans = plans;
        this.benefits = benefits;
        this.premiums = premiums;
        this.roles = roles;
    }

    /** Nothing for a non-funeral version -- its absence IS that. */
    void persist(UUID tenantId, UUID productVersionId, FuneralPlan plan) {
        if (plan == null || !plan.funeral()) {
            return;
        }
        terms.saveAndFlush(new FuneralTermsEntity(tenantId, productVersionId, plan));
        for (FuneralPlanOption option : plan.plans()) {
            plans.save(new FuneralPlanEntity(tenantId, productVersionId, option));
        }
        // The plan rows first: benefit and premium rows reference (version, plan_code).
        plans.flush();
        for (FuneralPlanBenefit benefit : plan.benefits()) {
            benefits.save(new FuneralPlanBenefitEntity(tenantId, productVersionId, benefit));
        }
        for (FuneralPremiumRow row : plan.premiums()) {
            premiums.save(new FuneralPremiumEntity(tenantId, productVersionId, row));
        }
        for (FuneralRoleRule rule : plan.roles()) {
            roles.save(new FuneralRoleRuleEntity(tenantId, productVersionId, rule));
        }
    }

    FuneralPlan read(UUID productVersionId) {
        return terms.findById(productVersionId)
            .map(t -> t.toPlan(
                plans.findByProductVersionIdOrderByPlanCode(productVersionId).stream().map(FuneralPlanEntity::toOption).toList(),
                benefits.findByProductVersionId(productVersionId).stream().map(FuneralPlanBenefitEntity::toBenefit).toList(),
                premiums.findByProductVersionIdOrderByPlanCodeAscRoleAscAgeFromAsc(productVersionId).stream()
                    .map(FuneralPremiumEntity::toRow).toList(),
                roles.findByProductVersionId(productVersionId).stream().map(FuneralRoleRuleEntity::toRule).toList()))
            .orElse(FuneralPlan.none());
    }
}
