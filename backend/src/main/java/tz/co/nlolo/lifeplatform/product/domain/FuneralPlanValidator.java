package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;
import tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A FUNERAL version's terms, refused at publish in words the console mirrors. The load-bearing rule is
 * coverage: every age a covered role can reach is priced exactly once, so a policy can never meet an age
 * its own version cannot price (the platform refuses rather than defaults).
 */
public final class FuneralPlanValidator {

    private FuneralPlanValidator() {}

    public static void validate(ProductCategory category, FuneralPlan plan,
                                List<ProductApi.BaseRateInput> baseRates, List<ProductApi.RatingFactorInput> ratingTable) {
        FuneralPlan terms = plan != null ? plan : FuneralPlan.none();
        if (category != ProductCategory.FUNERAL) {
            if (terms.funeral()) {
                throw refused("Funeral terms are only valid on a FUNERAL product");
            }
            return;
        }
        if (!terms.funeral()) {
            throw refused("A FUNERAL version must carry its plans, premium table and role rules");
        }
        // Plan R1: the premium table is the whole price; a multiplier on top would count age twice.
        if ((baseRates != null && !baseRates.isEmpty()) || (ratingTable != null && !ratingTable.isEmpty())) {
            throw refused("A FUNERAL version is priced by its premium table alone; remove the base rates and rating factors");
        }
        checkPlans(terms);
        checkRoles(terms);
        checkClaimRules(terms);
        for (FuneralPlanOption option : terms.plans()) {
            if (terms.benefit(option.planCode(), FuneralRole.MAIN_MEMBER).isEmpty()) {
                throw refused("Plan " + option.planCode() + " does not cover the main member");
            }
        }
        for (FuneralPlanBenefit benefit : terms.benefits()) {
            if (!terms.offersPlan(benefit.planCode())) {
                throw refused("A benefit names plan " + benefit.planCode() + ", which this version does not offer");
            }
            if (terms.rule(benefit.role()).isEmpty()) {
                throw refused("Plan " + benefit.planCode() + " covers " + benefit.role().plural() + ", but "
                    + benefit.role() + " has no role rule");
            }
            if (benefit.benefit() == null || benefit.benefit().signum() <= 0) {
                throw refused("Plan " + benefit.planCode() + ": the " + benefit.role().label() + "'s benefit must be above zero");
            }
        }
        for (FuneralPremiumRow row : terms.premiums()) {
            if (terms.benefit(row.planCode(), row.role()).isEmpty()) {
                throw refused("Plan " + row.planCode() + " prices " + row.role() + " but does not cover it");
            }
            if (row.yearlyPremium() == null || row.yearlyPremium().signum() <= 0 || row.ageFrom() < 0 || row.ageTo() < row.ageFrom()) {
                throw refused("Plan " + row.planCode() + ", " + row.role() + ": ages " + row.ageFrom() + "-" + row.ageTo()
                    + " need a premium above zero and an end age no earlier than the start");
            }
        }
        for (FuneralPlanBenefit benefit : terms.benefits()) {
            checkCoverage(terms, benefit.planCode(), benefit.role());
        }
    }

    private static void checkPlans(FuneralPlan terms) {
        if (terms.plans().isEmpty()) {
            throw refused("A FUNERAL version needs at least one plan");
        }
        Set<String> seen = new HashSet<>();
        for (FuneralPlanOption option : terms.plans()) {
            if (option.planCode() == null || option.planCode().isBlank()) {
                throw refused("Every plan needs a code");
            }
            if (!seen.add(option.planCode())) {
                throw refused("Plan code " + option.planCode() + " appears twice");
            }
        }
    }

    private static void checkRoles(FuneralPlan terms) {
        Set<FuneralRole> seen = new HashSet<>();
        for (FuneralRoleRule rule : terms.roles()) {
            if (!seen.add(rule.role())) {
                throw refused("Role " + rule.role() + " is configured twice");
            }
        }
        if (!seen.contains(FuneralRole.MAIN_MEMBER)) {
            throw refused("The main member's role rule is required");
        }
        for (FuneralRoleRule rule : terms.roles()) {
            if (rule.role() == FuneralRole.SPOUSE && rule.maxLives() != 1) {
                throw refused("One spouse per policy: SPOUSE maxLives must be 1");
            }
            if (rule.role() == FuneralRole.MAIN_MEMBER && rule.maxLives() != 1) {
                throw refused("A policy has one main member: MAIN_MEMBER maxLives must be 1");
            }
            if (rule.maxLives() < 1 || rule.minEntryAge() < 0 || rule.maxEntryAge() < rule.minEntryAge()) {
                throw refused("Role " + rule.role() + " needs at least one life and an entry age range, youngest first");
            }
            if (rule.coverStopAge() != null && rule.coverStopAge() <= rule.maxEntryAge()) {
                throw refused("Role " + rule.role() + ": cover must stop after the oldest entry age, "
                    + rule.maxEntryAge() + ", not at " + rule.coverStopAge());
            }
            if (rule.studentStopAge() != null && (rule.role() != FuneralRole.CHILD || rule.coverStopAge() == null
                    || rule.studentStopAge() <= rule.coverStopAge())) {
                throw refused("Only a child's cover can extend for a student, and to an age after it would otherwise stop");
            }
        }
        int oldestEntry = terms.roles().stream().filter(r -> r.coverStopAge() == null)
            .mapToInt(FuneralRoleRule::maxEntryAge).max().orElse(0);
        if (terms.maxPricedAge() < oldestEntry) {
            throw refused("The highest priced age, " + terms.maxPricedAge() + ", is below the oldest entry age, " + oldestEntry);
        }
    }

    private static void checkClaimRules(FuneralPlan terms) {
        if (terms.waitingPeriodMonths() != null && terms.waitingPeriodMonths() <= 0) {
            throw refused("A waiting period is a number of months above zero; leave it empty for none");
        }
        if (terms.dependantClaimPayee() == null) {
            throw refused("Choose who is paid when a dependant dies");
        }
        if (terms.onMainMemberDeath() == null) {
            throw refused("Choose what happens when the main member dies");
        }
    }

    /** Every age from the role's youngest entry to the last age it can be covered at, priced exactly once. */
    private static void checkCoverage(FuneralPlan terms, String planCode, FuneralRole role) {
        FuneralRoleRule rule = terms.rule(role).orElseThrow();
        List<FuneralPremiumRow> rows = terms.premiums().stream()
            .filter(p -> p.planCode().equals(planCode) && p.role() == role)
            .sorted(Comparator.comparingInt(FuneralPremiumRow::ageFrom)).toList();
        for (int i = 1; i < rows.size(); i++) {
            FuneralPremiumRow before = rows.get(i - 1);
            FuneralPremiumRow after = rows.get(i);
            if (after.ageFrom() <= before.ageTo()) {
                throw refused("Plan " + planCode + ", " + role + ": ages " + before.ageFrom() + "-" + before.ageTo()
                    + " and " + after.ageFrom() + "-" + after.ageTo() + " overlap");
            }
        }
        int lastAge = rule.coverStopAge() != null
            ? Math.max(rule.coverStopAge(), rule.studentStopAge() != null ? rule.studentStopAge() : 0) - 1
            : terms.maxPricedAge();
        for (int age = rule.minEntryAge(); age <= lastAge; age++) {
            int a = age;
            if (rows.stream().noneMatch(p -> p.ageFrom() <= a && a <= p.ageTo())) {
                throw refused("Plan " + planCode + ", " + role + ": no premium for age " + age);
            }
        }
    }

    private static InvalidProductVersionException refused(String message) {
        return new InvalidProductVersionException(message);
    }
}
