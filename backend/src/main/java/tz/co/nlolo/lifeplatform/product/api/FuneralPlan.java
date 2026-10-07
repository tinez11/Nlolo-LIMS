package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * A FUNERAL version's terms (spec 2026-10-04): the plans a customer picks from, what each plan pays per
 * role, the fixed yearly premium per role and age band, who may be covered in each role, and the claim
 * rules. Every rule is the version's own -- set by the staff who configure the product, nothing hard-coded
 * (spec Q3). {@link #none()} is every other version.
 *
 * @param maxPricedAge          the oldest age the premium table must price for a role whose cover never
 *                              stops for age
 * @param waitingPeriodMonths   months from a life's own cover start in which a natural death is not paid;
 *                              null = none
 * @param freeCoverToPaidDate   when the main member dies, whether the rest of the family stays covered to
 *                              the next premium date (plan R5); layered on either death rule
 * @param soldAs                individual policies, group schemes, or both (group funeral schemes, 2026-10-07)
 */
public record FuneralPlan(boolean funeral, List<FuneralPlanOption> plans, List<FuneralPlanBenefit> benefits,
                          List<FuneralPremiumRow> premiums, List<FuneralRoleRule> roles, int maxPricedAge,
                          Integer waitingPeriodMonths, boolean accidentWaivesWaiting,
                          DependantClaimPayee dependantClaimPayee, MainMemberDeathRule onMainMemberDeath,
                          boolean freeCoverToPaidDate, FuneralSoldAs soldAs) {

    public FuneralPlan {
        plans = plans != null ? List.copyOf(plans) : List.of();
        benefits = benefits != null ? List.copyOf(benefits) : List.of();
        premiums = premiums != null ? List.copyOf(premiums) : List.of();
        roles = roles != null ? List.copyOf(roles) : List.of();
        soldAs = soldAs != null ? soldAs : FuneralSoldAs.INDIVIDUAL;
    }

    /** A version sold to individuals only -- every version written before group schemes. */
    public FuneralPlan(boolean funeral, List<FuneralPlanOption> plans, List<FuneralPlanBenefit> benefits,
                       List<FuneralPremiumRow> premiums, List<FuneralRoleRule> roles, int maxPricedAge,
                       Integer waitingPeriodMonths, boolean accidentWaivesWaiting,
                       DependantClaimPayee dependantClaimPayee, MainMemberDeathRule onMainMemberDeath,
                       boolean freeCoverToPaidDate) {
        this(funeral, plans, benefits, premiums, roles, maxPricedAge, waitingPeriodMonths, accidentWaivesWaiting,
            dependantClaimPayee, onMainMemberDeath, freeCoverToPaidDate, FuneralSoldAs.INDIVIDUAL);
    }

    /** What a group scheme on this plan pays per member per month; empty when the plan has no group rate. */
    public Optional<BigDecimal> groupMonthlyRate(String planCode) {
        return plans.stream().filter(p -> p.planCode().equals(planCode)).map(FuneralPlanOption::groupMonthlyRate)
            .filter(java.util.Objects::nonNull).findFirst();
    }

    public static FuneralPlan none() {
        return new FuneralPlan(false, List.of(), List.of(), List.of(), List.of(), 0, null, false, null, null, false);
    }

    /** What this plan pays for a life in this role; empty when the plan does not cover the role. */
    public Optional<BigDecimal> benefit(String planCode, FuneralRole role) {
        return benefits.stream().filter(b -> b.planCode().equals(planCode) && b.role() == role)
            .map(FuneralPlanBenefit::benefit).findFirst();
    }

    public Optional<FuneralRoleRule> rule(FuneralRole role) {
        return roles.stream().filter(r -> r.role() == role).findFirst();
    }

    /** The yearly premium for one life aged {@code age}; empty when no row prices it. */
    public Optional<BigDecimal> yearlyPremium(String planCode, FuneralRole role, int age) {
        return premiums.stream()
            .filter(p -> p.planCode().equals(planCode) && p.role() == role && p.ageFrom() <= age && age <= p.ageTo())
            .map(FuneralPremiumRow::yearlyPremium).findFirst();
    }

    /** The age cover stops for a life in this role, or null when it never does (or the role has no rule). */
    public Integer stopAge(FuneralRole role, boolean student) {
        return rule(role).map(r -> student && r.studentStopAge() != null ? r.studentStopAge() : r.coverStopAge())
            .orElse(null);
    }

    public boolean offersPlan(String planCode) {
        return plans.stream().anyMatch(p -> p.planCode().equals(planCode));
    }
}
