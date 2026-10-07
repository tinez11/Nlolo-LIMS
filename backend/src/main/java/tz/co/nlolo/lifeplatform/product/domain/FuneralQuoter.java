package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.FuneralLifeInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuote;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteInput;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteLine;
import tz.co.nlolo.lifeplatform.product.api.FuneralQuoteRefusedException;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;
import tz.co.nlolo.lifeplatform.product.api.PremiumFrequency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The one funeral pricer. Underwriting quotes the family on it, issuance takes the policy's premium from it,
 * and every later restatement adds yearly premiums the same way: sum the lives' yearly premiums, load the
 * total for the frequency, divide once, round once (HALF_EVEN, to cents).
 */
public final class FuneralQuoter {

    private FuneralQuoter() {}

    public static FuneralQuote quote(FuneralPlan plan, FrequencyLoading loading, FuneralQuoteInput in) {
        if (!plan.funeral()) {
            throw new FuneralQuoteRefusedException("This product is not a funeral plan");
        }
        if (!plan.soldAs().individual()) {
            throw new FuneralQuoteRefusedException("This product is sold to group schemes only; a scheme pays its plan's"
                + " group rate per member");
        }
        if (!plan.offersPlan(in.planCode())) {
            throw new FuneralQuoteRefusedException("There is no plan " + in.planCode() + " on this product");
        }
        if (in.frequency() == null || in.frequency() == PremiumFrequency.SINGLE) {
            throw new FuneralQuoteRefusedException("A funeral plan is paid monthly, quarterly or annually");
        }
        long mains = in.lives().stream().filter(l -> l.role() == FuneralRole.MAIN_MEMBER).count();
        if (mains != 1) {
            throw new FuneralQuoteRefusedException("A funeral plan covers exactly one main member");
        }
        Map<FuneralRole, Integer> counts = new EnumMap<>(FuneralRole.class);
        in.lives().forEach(l -> counts.merge(l.role(), 1, Integer::sum));

        List<FuneralQuoteLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (FuneralLifeInput life : in.lives()) {
            FuneralQuoteLine line = priced(plan, in.planCode(), life, counts.get(life.role()), in.asOf());
            lines.add(line);
            total = total.add(line.yearlyPremium());
        }
        return new FuneralQuote(in.planCode(), in.frequency(), lines, total, instalment(total, loading, in.frequency()),
            plan.benefit(in.planCode(), FuneralRole.MAIN_MEMBER).orElseThrow());
    }

    /**
     * One life joining a family already on cover: the same rules a whole-family quote applies to each life,
     * with {@code alreadyInRole} the lives that will still hold that role. The existing lives are NOT re-checked
     * against entry ages -- a main member who has turned 66 is still covered, and must not block a new baby.
     */
    public static FuneralQuoteLine admit(FuneralPlan plan, String planCode, FuneralLifeInput life, int alreadyInRole,
                                         LocalDate asOf) {
        if (life.role() == FuneralRole.MAIN_MEMBER) {
            throw new FuneralQuoteRefusedException("A funeral plan covers exactly one main member");
        }
        return priced(plan, planCode, life, alreadyInRole + 1, asOf);
    }

    /**
     * A family on a group scheme (group funeral schemes, 2026-10-07): the role rules a quote applies -- the plan covers
     * the role, the role's most lives, entry ages on {@code asOf}, a student only a child -- but unpriced, since a
     * scheme pays its plan's group rate per member. Every problem at once, in the quote's words prefixed by the life's
     * name, so a schedule can be corrected in one pass. Empty when the family may join.
     */
    public static List<String> familyProblems(FuneralPlan plan, String planCode, LocalDate asOf, List<FuneralLifeInput> lives) {
        List<String> problems = new ArrayList<>();
        if (!plan.funeral()) {
            return List.of("This product is not a funeral plan");
        }
        if (!plan.soldAs().group()) {
            return List.of("This product is not sold to group schemes");
        }
        if (!plan.offersPlan(planCode)) {
            return List.of("There is no plan " + planCode + " on this product");
        }
        long mains = lives.stream().filter(l -> l.role() == FuneralRole.MAIN_MEMBER).count();
        if (mains != 1) {
            problems.add("A family has exactly one main member, not " + mains);
        }
        Map<FuneralRole, Integer> counts = new EnumMap<>(FuneralRole.class);
        lives.stream().filter(l -> l.role() != null).forEach(l -> counts.merge(l.role(), 1, Integer::sum));
        counts.forEach((role, n) -> plan.rule(role).ifPresent(rule -> {
            if (n > rule.maxLives()) {
                problems.add("At most " + rule.maxLives() + " " + (rule.maxLives() == 1 ? role.label() : role.plural())
                    + " may be covered, not " + n);
            }
        }));
        for (FuneralLifeInput life : lives) {
            problems.addAll(lifeProblems(plan, planCode, life, asOf));
        }
        return problems;
    }

    /** One life's entry problems, unpriced and without the count rule (the family's or the caller's to apply). */
    public static List<String> lifeProblems(FuneralPlan plan, String planCode, FuneralLifeInput life, LocalDate asOf) {
        String who = life.name() == null || life.name().isBlank() ? "A life" : life.name();
        FuneralRole role = life.role();
        if (role == null) {
            return List.of(who + ": the role is required");
        }
        if (plan.rule(role).isEmpty()) {
            return List.of(who + ": this product does not cover " + withArticle(role));
        }
        if (plan.benefit(planCode, role).isEmpty()) {
            return List.of(who + ": plan " + planCode + " does not cover " + role.plural());
        }
        if (life.dateOfBirth() == null) {
            return List.of(who + ": the date of birth is required");
        }
        List<String> problems = new ArrayList<>();
        FuneralRoleRule rule = plan.rule(role).orElseThrow();
        int age = Period.between(life.dateOfBirth(), asOf).getYears();
        if (age < rule.minEntryAge() || age > rule.maxEntryAge()) {
            problems.add(who + ": " + withArticle(role) + " must be " + rule.minEntryAge() + " to " + rule.maxEntryAge()
                + " at entry, not " + age);
        }
        if (life.student() && role != FuneralRole.CHILD) {
            problems.add(who + ": only a child can be marked as a student");
        }
        return problems;
    }

    /** {@code inRole}: how many lives will hold this life's role, this one included. */
    private static FuneralQuoteLine priced(FuneralPlan plan, String planCode, FuneralLifeInput life, int inRole, LocalDate asOf) {
        FuneralRole role = life.role();
        if (role == null) {
            throw new FuneralQuoteRefusedException(life.name() + ": the role is required");
        }
        FuneralRoleRule rule = plan.rule(role).orElseThrow(() ->
            new FuneralQuoteRefusedException("This product does not cover " + withArticle(role)));
        if (inRole > rule.maxLives()) {
            throw new FuneralQuoteRefusedException("At most " + rule.maxLives() + " "
                + (rule.maxLives() == 1 ? role.label() : role.plural()) + " may be covered");
        }
        BigDecimal benefit = plan.benefit(planCode, role).orElseThrow(() ->
            new FuneralQuoteRefusedException("Plan " + planCode + " does not cover " + role.plural()));
        if (life.dateOfBirth() == null) {
            throw new FuneralQuoteRefusedException(life.name() + ": the date of birth is required");
        }
        int age = Period.between(life.dateOfBirth(), asOf).getYears();
        if (age < rule.minEntryAge() || age > rule.maxEntryAge()) {
            throw new FuneralQuoteRefusedException(life.name() + ": " + withArticle(role) + " must be "
                + rule.minEntryAge() + " to " + rule.maxEntryAge() + " at entry, not " + age);
        }
        if (life.student() && role != FuneralRole.CHILD) {
            throw new FuneralQuoteRefusedException(life.name() + ": only a child can be marked as a student");
        }
        return new FuneralQuoteLine(role, life.name(), age, benefit, yearlyPremiumAt(plan, planCode, role, age));
    }

    /**
     * One life's yearly premium at {@code age}, with no entry-age check: an existing life is re-priced at its
     * anniversary long after it was old enough to join. Refused only where the table has no row.
     */
    public static BigDecimal yearlyPremiumAt(FuneralPlan plan, String planCode, FuneralRole role, int age) {
        return plan.yearlyPremium(planCode, role, age).orElseThrow(() -> new FuneralQuoteRefusedException(
            "Plan " + planCode + " has no premium for " + withArticle(role) + " aged " + age));
    }

    /** The family's yearly total, loaded for the frequency and divided once, rounded once. */
    public static BigDecimal instalment(BigDecimal totalYearly, FrequencyLoading loading, PremiumFrequency frequency) {
        BigDecimal loaded = loading.applyTo(totalYearly, frequency);
        return loaded.divide(BigDecimal.valueOf(frequency.instalmentsPerYear()), 2, RoundingMode.HALF_EVEN);
    }

    private static String withArticle(FuneralRole role) {
        return (role == FuneralRole.EXTENDED ? "an " : "a ") + role.label();
    }
}
