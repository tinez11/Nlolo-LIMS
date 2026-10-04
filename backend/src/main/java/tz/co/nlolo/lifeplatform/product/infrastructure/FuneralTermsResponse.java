package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.DependantClaimPayee;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;
import tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;
import tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule;

import java.util.List;

/**
 * A FUNERAL version's terms, for the product screen and the case form. Unlike the annuity read, the
 * premium table IS here: the application screen shows each life's premium, and agents sell this product.
 */
public record FuneralTermsResponse(List<FuneralPlanOption> plans, List<FuneralPlanBenefit> benefits,
                                   List<FuneralPremiumRow> premiums, List<FuneralRoleRule> roles, int maxPricedAge,
                                   Integer waitingPeriodMonths, boolean accidentWaivesWaiting,
                                   DependantClaimPayee dependantClaimPayee, MainMemberDeathRule onMainMemberDeath,
                                   boolean freeCoverToPaidDate) {

    static FuneralTermsResponse from(FuneralPlan plan) {
        return new FuneralTermsResponse(plan.plans(), plan.benefits(), plan.premiums(), plan.roles(), plan.maxPricedAge(),
            plan.waitingPeriodMonths(), plan.accidentWaivesWaiting(), plan.dependantClaimPayee(),
            plan.onMainMemberDeath(), plan.freeCoverToPaidDate());
    }
}
