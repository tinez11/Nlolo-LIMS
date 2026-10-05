package tz.co.nlolo.lifeplatform.product;

import tz.co.nlolo.lifeplatform.product.api.DependantClaimPayee;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;
import tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;
import tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.CHILD;
import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.EXTENDED;
import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.MAIN_MEMBER;
import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.PARENT;
import static tz.co.nlolo.lifeplatform.product.api.FuneralRole.SPOUSE;

/**
 * "Familia": the spec's example family plan, shared by every funeral test. Plans A and B; plan B pays
 * 2,000,000 for the main member and spouse, 1,000,000 for a child or parent, 500,000 for extended family;
 * plan A pays half. Premiums are banded so every age each role can reach is priced exactly once.
 */
public final class FuneralPlans {
    private FuneralPlans() {}

    public static List<FuneralPlanOption> plans() {
        return List.of(new FuneralPlanOption("A", "Familia A"), new FuneralPlanOption("B", "Familia B"));
    }

    public static List<FuneralPlanBenefit> benefits() {
        List<FuneralPlanBenefit> rows = new ArrayList<>();
        for (String plan : List.of("A", "B")) {
            BigDecimal scale = "A".equals(plan) ? new BigDecimal("0.5") : BigDecimal.ONE;
            rows.add(new FuneralPlanBenefit(plan, MAIN_MEMBER, money("2000000", scale)));
            rows.add(new FuneralPlanBenefit(plan, SPOUSE, money("2000000", scale)));
            rows.add(new FuneralPlanBenefit(plan, CHILD, money("1000000", scale)));
            rows.add(new FuneralPlanBenefit(plan, PARENT, money("1000000", scale)));
            rows.add(new FuneralPlanBenefit(plan, EXTENDED, money("500000", scale)));
        }
        return rows;
    }

    /** Plan B's yearly premiums; plan A charges half. */
    public static List<FuneralPremiumRow> premiums() {
        List<FuneralPremiumRow> rows = new ArrayList<>();
        for (String plan : List.of("A", "B")) {
            BigDecimal scale = "A".equals(plan) ? new BigDecimal("0.5") : BigDecimal.ONE;
            for (FuneralRole adult : List.of(MAIN_MEMBER, SPOUSE)) {
                rows.add(row(plan, adult, 18, 35, "36000", scale));
                rows.add(row(plan, adult, 36, 50, "60000", scale));
                rows.add(row(plan, adult, 51, 65, "96000", scale));
                rows.add(row(plan, adult, 66, 100, "150000", scale));
            }
            rows.add(row(plan, CHILD, 0, 24, "6000", scale));
            rows.add(row(plan, PARENT, 18, 65, "48000", scale));
            rows.add(row(plan, PARENT, 66, 100, "90000", scale));
            rows.add(row(plan, EXTENDED, 0, 35, "12000", scale));
            rows.add(row(plan, EXTENDED, 36, 65, "30000", scale));
            rows.add(row(plan, EXTENDED, 66, 100, "70000", scale));
        }
        return rows;
    }

    /** The spec's defaults: one spouse, six children to 21 (25 a student), four parents to 75, four extended to 65. */
    public static List<FuneralRoleRule> roles() {
        return List.of(
            new FuneralRoleRule(MAIN_MEMBER, 1, 18, 65, null, null),
            new FuneralRoleRule(SPOUSE, 1, 18, 65, null, null),
            new FuneralRoleRule(CHILD, 6, 0, 20, 21, 25),
            new FuneralRoleRule(PARENT, 4, 18, 75, null, null),
            new FuneralRoleRule(EXTENDED, 4, 0, 65, null, null));
    }

    public static FuneralPlan of(List<FuneralPlanOption> plans, List<FuneralPlanBenefit> benefits,
                                 List<FuneralPremiumRow> premiums, List<FuneralRoleRule> roles) {
        return new FuneralPlan(true, plans, benefits, premiums, roles, 100, 6, true,
            DependantClaimPayee.MAIN_MEMBER, MainMemberDeathRule.POLICY_ENDS, true);
    }

    /** Waiting 6 months, accidents waived, a dependant's death paid to the main member, the policy ends with free cover. */
    public static FuneralPlan familia() {
        return of(plans(), benefits(), premiums(), roles());
    }

    /** {@link #familia()} with other claim rules. */
    public static FuneralPlan familia(Integer waitingMonths, DependantClaimPayee payee, MainMemberDeathRule onDeath,
                                      boolean freeCover) {
        return new FuneralPlan(true, plans(), benefits(), premiums(), roles(), 100, waitingMonths, true, payee, onDeath,
            freeCover);
    }

    private static FuneralPremiumRow row(String plan, FuneralRole role, int from, int to, String yearly, BigDecimal scale) {
        return new FuneralPremiumRow(plan, role, from, to, money(yearly, scale));
    }

    private static BigDecimal money(String amount, BigDecimal scale) {
        return new BigDecimal(amount).multiply(scale).setScale(2);
    }
}
