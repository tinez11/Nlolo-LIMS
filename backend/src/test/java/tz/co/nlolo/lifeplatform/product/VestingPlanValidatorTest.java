package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.VestingPlanValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** VestingPlanValidator, rule by rule, in the exact words the console mirrors (product step 5 D2). */
class VestingPlanValidatorTest {

    private static final EligibilityBounds AGES_18_TO_55 = new EligibilityBounds(18, 55, null, null, null, null);
    private static final AccumulationPlan ACCOUNT = new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ONE, BigDecimal.ZERO,
        List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

    private static AnnuityForm lifeOnly() {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 55; age <= 70; age++) {
            rows.add(new AnnuityRateRow(null, age, null, null, new BigDecimal(60 + age - 55)));
        }
        return new AnnuityForm("LIFE-0G", 0, false, null, BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, rows);
    }

    private static AnnuityForm jointHalf() {
        List<AnnuityRateRow> rows = new ArrayList<>();
        for (int age = 55; age <= 70; age++) {
            rows.add(new AnnuityRateRow(null, age, -10, 15, new BigDecimal(50 + age - 55)));
        }
        return new AnnuityForm("JOINT-50", 0, true, new BigDecimal("50"), BigDecimal.ZERO, false, AnnuityRateBasis.UNISEX, rows);
    }

    private static AnnuityPlan plan(VestingTerms terms) {
        return new AnnuityPlan(true, AnnuityTiming.ARREARS, 12, -10, 15, "ACT/ANN/2026", LocalDate.of(2026, 1, 1),
            List.of(lifeOnly(), jointHalf()),
            List.of(new AnnuityFrequencyFactor("MONTHLY", new BigDecimal("0.98")), new AnnuityFrequencyFactor("ANNUAL", BigDecimal.ONE)),
            terms);
    }

    private static VestingTerms terms() {
        return new VestingTerms(55, 70, "LIFE-0G", "MONTHLY", new BigDecimal("25"), Boolean.FALSE);
    }

    private static void refused(Runnable r, String message) {
        assertThatThrownBy(r::run).isInstanceOf(InvalidProductVersionException.class).hasMessage(message);
    }

    private static void refused(VestingTerms terms, String message) {
        refused(() -> VestingPlanValidator.validate(ProductCategory.ANNUITY, plan(terms), ACCOUNT, AGES_18_TO_55), message);
    }

    @Test
    void aValidDeferredVersionPasses() {
        assertThatCode(() -> VestingPlanValidator.validate(ProductCategory.ANNUITY, plan(terms()), ACCOUNT, AGES_18_TO_55))
            .doesNotThrowAnyException();
    }

    @Test
    void anImmediateAnnuityWithoutAnAccountIsNotChecked() {
        assertThatCode(() -> VestingPlanValidator.validate(ProductCategory.ANNUITY, plan(null), AccumulationPlan.none(),
            AGES_18_TO_55)).doesNotThrowAnyException();
        assertThatCode(() -> VestingPlanValidator.validate(ProductCategory.ENDOWMENT, AnnuityPlan.none(), ACCOUNT,
            AGES_18_TO_55)).doesNotThrowAnyException();
    }

    @Test
    void vestingTermsAreOnlyForAnAnnuity() {
        refused(() -> VestingPlanValidator.validate(ProductCategory.ENDOWMENT, plan(terms()), ACCOUNT, AGES_18_TO_55),
            "Vesting terms are only for an ANNUITY product");
    }

    @Test
    void vestingTermsNeedAnAccount() {
        refused(() -> VestingPlanValidator.validate(ProductCategory.ANNUITY, plan(terms()), AccumulationPlan.none(), AGES_18_TO_55),
            "Vesting terms are only for an annuity that saves in an account before it vests");
    }

    @Test
    void anAnnuityThatSavesMustStateItsVestingTerms() {
        refused(() -> VestingPlanValidator.validate(ProductCategory.ANNUITY, plan(null), ACCOUNT, AGES_18_TO_55),
            "An annuity that saves in an account must state its vesting terms");
    }

    @Test
    void theWindowRunsFromMinToMaxWithinZeroTo120() {
        String message = "The vesting window runs from a minimum to a maximum vesting age, each between 0 and 120";
        refused(new VestingTerms(70, 55, "LIFE-0G", "MONTHLY", BigDecimal.TEN, Boolean.FALSE), message);
        refused(new VestingTerms(-1, 70, "LIFE-0G", "MONTHLY", BigDecimal.TEN, Boolean.FALSE), message);
        refused(new VestingTerms(55, 121, "LIFE-0G", "MONTHLY", BigDecimal.TEN, Boolean.FALSE), message);
    }

    @Test
    void theDefaultFormMustBeOnTheVersion() {
        refused(new VestingTerms(55, 70, "LIFE-5G", "MONTHLY", BigDecimal.TEN, Boolean.FALSE),
            "The default form LIFE-5G is not one of this version's forms");
    }

    @Test
    void theDefaultFormMayNotBeJoint() {
        refused(new VestingTerms(55, 70, "JOINT-50", "MONTHLY", BigDecimal.ZERO, Boolean.FALSE),
            "The default form JOINT-50 is joint-life; a pension that vests with no instruction vests on one life");
    }

    @Test
    void theDefaultFrequencyMustBeOnTheVersion() {
        refused(new VestingTerms(55, 70, "LIFE-0G", "QUARTERLY", BigDecimal.TEN, Boolean.FALSE),
            "The default frequency QUARTERLY is not one of this version's frequencies");
    }

    @Test
    void theLumpSumCapIsBetweenZeroAndAHundred() {
        String message = "The lump-sum cap must be between 0% and 100% of the balance";
        refused(new VestingTerms(55, 70, "LIFE-0G", "MONTHLY", null, Boolean.FALSE), message);
        refused(new VestingTerms(55, 70, "LIFE-0G", "MONTHLY", new BigDecimal("-0.01"), Boolean.FALSE), message);
        refused(new VestingTerms(55, 70, "LIFE-0G", "MONTHLY", new BigDecimal("100.01"), Boolean.FALSE), message);
    }

    @Test
    void theVersionMustSayWhetherItIsLocked() {
        refused(new VestingTerms(55, 70, "LIFE-0G", "MONTHLY", BigDecimal.TEN, null),
            "A deferred annuity must state whether it can be surrendered before it vests");
    }

    @Test
    void everyCustomerCanReachAVestingAge() {
        refused(() -> VestingPlanValidator.validate(ProductCategory.ANNUITY,
                plan(new VestingTerms(55, 55, "LIFE-0G", "MONTHLY", BigDecimal.TEN, Boolean.TRUE)), ACCOUNT, AGES_18_TO_55),
            "The maximum entry age must be below the maximum vesting age, so every customer can reach a vesting age");
    }
}
