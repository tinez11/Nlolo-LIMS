package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.Valid;
import tz.co.nlolo.lifeplatform.product.api.DependantClaimPayee;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;
import tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;
import tz.co.nlolo.lifeplatform.product.api.FuneralSoldAs;
import tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule;

import java.math.BigDecimal;
import java.util.List;

/**
 * A FUNERAL version's terms on the wire. Nothing is @NotNull: the validator refuses an absence in the
 * words the console mirrors, and a bean-validation 400 would say something else (AnnuityRequest's reason).
 * A missing number becomes -1 so a rule refuses it in its own words rather than an NPE.
 */
public record FuneralRequest(@Valid List<Plan> plans, @Valid List<Benefit> benefits, @Valid List<Premium> premiums,
                             @Valid List<RoleRule> roles, Integer maxPricedAge, Integer waitingPeriodMonths,
                             Boolean accidentWaivesWaiting, DependantClaimPayee dependantClaimPayee,
                             MainMemberDeathRule onMainMemberDeath, Boolean freeCoverToPaidDate,
                             FuneralSoldAs soldAs) {

    /**
     * {@code groupMonthlyRate}: the group rate per member, per {@code groupRatePeriod} (MONTHLY when absent; YEARLY bills
     * a scheme once a year with its member list fixed); absent on a version not sold to groups.
     */
    public record Plan(String planCode, String name, BigDecimal groupMonthlyRate,
                       tz.co.nlolo.lifeplatform.product.api.GroupRatePeriod groupRatePeriod) {}

    public record Benefit(String planCode, FuneralRole role, BigDecimal benefit) {}

    public record Premium(String planCode, FuneralRole role, Integer ageFrom, Integer ageTo, BigDecimal yearlyPremium) {}

    public record RoleRule(FuneralRole role, Integer maxLives, Integer minEntryAge, Integer maxEntryAge,
                           Integer coverStopAge, Integer studentStopAge) {}

    public FuneralPlan toPlan() {
        return new FuneralPlan(true,
            plans == null ? List.of() : plans.stream()
                .map(p -> new FuneralPlanOption(p.planCode(), p.name(), p.groupMonthlyRate(), p.groupRatePeriod())).toList(),
            benefits == null ? List.of() : benefits.stream()
                .map(b -> new FuneralPlanBenefit(b.planCode(), b.role(), b.benefit())).toList(),
            premiums == null ? List.of() : premiums.stream()
                .map(p -> new FuneralPremiumRow(p.planCode(), p.role(), orMinusOne(p.ageFrom()), orMinusOne(p.ageTo()),
                    p.yearlyPremium())).toList(),
            roles == null ? List.of() : roles.stream()
                .map(r -> new FuneralRoleRule(r.role(), orMinusOne(r.maxLives()), orMinusOne(r.minEntryAge()),
                    orMinusOne(r.maxEntryAge()), r.coverStopAge(), r.studentStopAge())).toList(),
            orMinusOne(maxPricedAge), waitingPeriodMonths, Boolean.TRUE.equals(accidentWaivesWaiting),
            dependantClaimPayee, onMainMemberDeath, Boolean.TRUE.equals(freeCoverToPaidDate),
            soldAs != null ? soldAs : FuneralSoldAs.INDIVIDUAL);
    }

    private static int orMinusOne(Integer value) {
        return value != null ? value : -1;
    }
}
