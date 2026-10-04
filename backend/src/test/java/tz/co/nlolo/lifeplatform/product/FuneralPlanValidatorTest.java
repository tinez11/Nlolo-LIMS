package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.DependantClaimPayee;
import tz.co.nlolo.lifeplatform.product.api.FactorType;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlan;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;
import tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;
import tz.co.nlolo.lifeplatform.product.api.FuneralRoleRule;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.MainMemberDeathRule;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.api.Sex;
import tz.co.nlolo.lifeplatform.product.api.SmokerStatus;
import tz.co.nlolo.lifeplatform.product.domain.FuneralPlanValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.nlolo.lifeplatform.product.FuneralPlans.benefits;
import static tz.co.nlolo.lifeplatform.product.FuneralPlans.familia;
import static tz.co.nlolo.lifeplatform.product.FuneralPlans.of;
import static tz.co.nlolo.lifeplatform.product.FuneralPlans.plans;
import static tz.co.nlolo.lifeplatform.product.FuneralPlans.premiums;
import static tz.co.nlolo.lifeplatform.product.FuneralPlans.roles;

/** FuneralPlanValidator, rule by rule, in the exact words the console mirrors. */
class FuneralPlanValidatorTest {

    private static void validate(ProductCategory category, FuneralPlan plan) {
        FuneralPlanValidator.validate(category, plan, List.of(), List.of());
    }

    private static void refused(Runnable r, String message) {
        assertThatThrownBy(r::run).isInstanceOf(InvalidProductVersionException.class).hasMessage(message);
    }

    private static <T> List<T> without(List<T> rows, java.util.function.Predicate<T> drop) {
        return rows.stream().filter(drop.negate()).toList();
    }

    private static <T> List<T> with(List<T> rows, T extra) {
        List<T> out = new ArrayList<>(rows);
        out.add(extra);
        return out;
    }

    @Test
    void aValidFamilyPlanPasses() {
        assertThatCode(() -> validate(ProductCategory.FUNERAL, familia())).doesNotThrowAnyException();
    }

    @Test
    void everyOtherCategoryPassesWithNoFuneralTerms() {
        assertThatCode(() -> validate(ProductCategory.TERM_LIFE, FuneralPlan.none())).doesNotThrowAnyException();
    }

    @Test
    void funeralTermsOffAFuneralProductAreRefused() {
        refused(() -> validate(ProductCategory.TERM_LIFE, familia()), "Funeral terms are only valid on a FUNERAL product");
    }

    @Test
    void aFuneralVersionWithoutTermsIsRefused() {
        refused(() -> validate(ProductCategory.FUNERAL, FuneralPlan.none()),
            "A FUNERAL version must carry its plans, premium table and role rules");
    }

    @Test
    void baseRatesOrRatingFactorsAreRefused() {
        String message = "A FUNERAL version is priced by its premium table alone; remove the base rates and rating factors";
        refused(() -> FuneralPlanValidator.validate(ProductCategory.FUNERAL, familia(),
            List.of(new ProductApi.BaseRateInput(18, 60, Sex.MALE, SmokerStatus.NON_SMOKER, BigDecimal.ONE)), List.of()),
            message);
        refused(() -> FuneralPlanValidator.validate(ProductCategory.FUNERAL, familia(), List.of(),
            List.of(new ProductApi.RatingFactorInput(FactorType.AGE, "18-80", BigDecimal.ONE, 18, 80))), message);
    }

    @Test
    void aVersionWithNoPlanIsRefused() {
        refused(() -> validate(ProductCategory.FUNERAL, of(List.of(), List.of(), List.of(), roles())),
            "A FUNERAL version needs at least one plan");
    }

    @Test
    void aDuplicatePlanCodeIsRefused() {
        refused(() -> validate(ProductCategory.FUNERAL, of(with(plans(), new FuneralPlanOption("A", "Again")),
            benefits(), premiums(), roles())), "Plan code A appears twice");
    }

    @Test
    void theMainMembersRoleRuleIsRequired() {
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(), benefits(), premiums(),
            without(roles(), r -> r.role() == FuneralRole.MAIN_MEMBER))), "The main member's role rule is required");
    }

    @Test
    void aRoleConfiguredTwiceIsRefused() {
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(), benefits(), premiums(),
            with(roles(), new FuneralRoleRule(FuneralRole.CHILD, 2, 0, 18, 19, null)))), "Role CHILD is configured twice");
    }

    @Test
    void oneSpousePerPolicy() {
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(), benefits(), premiums(),
            with(without(roles(), r -> r.role() == FuneralRole.SPOUSE), new FuneralRoleRule(FuneralRole.SPOUSE, 2, 18, 65, null, null)))),
            "One spouse per policy: SPOUSE maxLives must be 1");
    }

    @Test
    void everyPlanCoversTheMainMember() {
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(),
            without(benefits(), b -> b.planCode().equals("B") && b.role() == FuneralRole.MAIN_MEMBER),
            without(premiums(), p -> p.planCode().equals("B") && p.role() == FuneralRole.MAIN_MEMBER), roles())),
            "Plan B does not cover the main member");
    }

    @Test
    void aBenefitForARoleWithNoRuleIsRefused() {
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(), benefits(), premiums(),
            without(roles(), r -> r.role() == FuneralRole.PARENT))), "Plan A covers parents, but PARENT has no role rule");
    }

    @Test
    void overlappingPremiumBandsAreRefused() {
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(), benefits(),
            with(premiums(), new FuneralPremiumRow("A", FuneralRole.CHILD, 5, 10, new BigDecimal("100.00"))), roles())),
            "Plan A, CHILD: ages 0-24 and 5-10 overlap");
    }

    @Test
    void anAgeTheRoleCanReachWithNoPremiumIsRefused() {
        // A student child is covered to 25, so the table must price 0-24.
        List<FuneralPremiumRow> rows = without(premiums(), p -> p.planCode().equals("A") && p.role() == FuneralRole.CHILD);
        rows = with(rows, new FuneralPremiumRow("A", FuneralRole.CHILD, 0, 20, new BigDecimal("3000.00")));
        List<FuneralPremiumRow> finalRows = rows;
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(), benefits(), finalRows, roles())),
            "Plan A, CHILD: no premium for age 21");
    }

    @Test
    void aRoleThatNeverStopsIsPricedToTheHighestPricedAge() {
        List<FuneralPremiumRow> rows = without(premiums(),
            p -> p.planCode().equals("B") && p.role() == FuneralRole.PARENT && p.ageFrom() == 66);
        rows = with(rows, new FuneralPremiumRow("B", FuneralRole.PARENT, 66, 90, new BigDecimal("90000.00")));
        List<FuneralPremiumRow> finalRows = rows;
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(), benefits(), finalRows, roles())),
            "Plan B, PARENT: no premium for age 91");
    }

    @Test
    void premiumsForARoleThePlanDoesNotCoverAreRefused() {
        refused(() -> validate(ProductCategory.FUNERAL, of(plans(),
            without(benefits(), b -> b.planCode().equals("A") && b.role() == FuneralRole.EXTENDED), premiums(), roles())),
            "Plan A prices EXTENDED but does not cover it");
    }

    @Test
    void aNonPositiveWaitingPeriodIsRefused() {
        refused(() -> validate(ProductCategory.FUNERAL, familia(0, DependantClaimPayee.MAIN_MEMBER, MainMemberDeathRule.POLICY_ENDS, true)),
            "A waiting period is a number of months above zero; leave it empty for none");
    }

    @Test
    void thePayeeAndTheDeathRuleMustBeChosen() {
        refused(() -> validate(ProductCategory.FUNERAL, familia(6, null, MainMemberDeathRule.POLICY_ENDS, true)),
            "Choose who is paid when a dependant dies");
        refused(() -> validate(ProductCategory.FUNERAL, familia(6, DependantClaimPayee.MAIN_MEMBER, null, true)),
            "Choose what happens when the main member dies");
    }

    @Test
    void theHighestPricedAgeMustCoverEveryEntryAge() {
        FuneralPlan low = new FuneralPlan(true, plans(), benefits(), premiums(), roles(), 60, 6, true,
            DependantClaimPayee.MAIN_MEMBER, MainMemberDeathRule.POLICY_ENDS, true);
        refused(() -> validate(ProductCategory.FUNERAL, low),
            "The highest priced age, 60, is below the oldest entry age, 75");
    }
}
