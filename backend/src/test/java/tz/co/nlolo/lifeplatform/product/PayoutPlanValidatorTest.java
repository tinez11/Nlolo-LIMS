package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.PayoutPlanValidator;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-category rules for a payout schedule (step 2 spec §3.2).
 *
 * <p>Pure, so it runs in milliseconds with no Spring context and no container -- which is the whole
 * reason the rules live in a static validator rather than inline in {@code publishVersion}, where
 * exercising one would cost a Postgres container.
 */
class PayoutPlanValidatorTest {

    private static final PayoutTerms FREE_LOOK_15 = new PayoutTerms(15, null, null, null);
    private static final PayoutRowInput MATURITY_100 =
        new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null);
    private static final PayoutRowInput SURVIVAL_Y5 =
        new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL);
    private static final PayoutRowInput INCOME_6_15 =
        new PayoutRowInput(PayoutKind.INCOME, 6, 15, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("3"), PayoutFrequency.MONTHLY);
    private static final PayoutRowInput ROP_100 =
        new PayoutRowInput(PayoutKind.RETURN_OF_PREMIUM, null, null, PayoutAmountBasis.PERCENT_OF_PREMIUMS, new BigDecimal("100"), null);

    @Test
    void aLegacyPlanIsAlwaysAccepted() {
        // The 40-odd internal publishVersion fixtures pass none(); holding them to the authoring
        // rules would fail every one of them for a product nobody is authoring.
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT, PayoutPlan.none()))
            .doesNotThrowAnyException();
    }

    @Test
    void anAuthoredIndividualProductNeedsFreeLookDays() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.TERM_LIFE,
                PayoutPlan.authored(PayoutTerms.none(), List.of())))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessage("A free-look period in days is required on an individual product");
    }

    @Test
    void groupAndCreditLifeNeedNoFreeLookAndTakeNoRows() {
        // Their cancellation is the scheme contract's, not a statutory window on one person.
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.GROUP_LIFE,
            PayoutPlan.authored(PayoutTerms.none(), List.of()))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.CREDIT_LIFE,
                PayoutPlan.authored(PayoutTerms.none(), List.of(MATURITY_100))))
            .hasMessage("A CREDIT_LIFE product cannot carry a payout schedule");
    }

    @Test
    void anEndowmentNeedsExactlyOneMaturityRow() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of())))
            .hasMessage("An ENDOWMENT product must carry exactly one MATURITY row");
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
            PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100)))).doesNotThrowAnyException();
    }

    @Test
    void survivalRowsNeedTheDeductionSetting() {
        // Guide §7: whether a survival benefit already paid comes off the death benefit is a
        // product decision, and a default either way would silently under- or over-pay a family.
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100, SURVIVAL_Y5))))
            .hasMessage("A product with SURVIVAL rows must say whether survival benefits paid are deducted from the death benefit");
        PayoutTerms withDeduction = new PayoutTerms(15, null, true, null);
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
            PayoutPlan.authored(withDeduction, List.of(MATURITY_100, SURVIVAL_Y5)))).doesNotThrowAnyException();
    }

    @Test
    void incomeRowsNeedAProofOfLifeInterval() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.EDUCATION_SAVINGS,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100, INCOME_6_15))))
            .hasMessage("A product with INCOME rows needs a proof-of-life interval in months");
    }

    @Test
    void termLifeTakesOnlyOneReturnOfPremiumRow() {
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.TERM_LIFE,
            PayoutPlan.authored(FREE_LOOK_15, List.of(ROP_100)))).doesNotThrowAnyException();
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.TERM_LIFE,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100))))
            .hasMessage("A TERM_LIFE product may carry only a single RETURN_OF_PREMIUM row");
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100, ROP_100))))
            .hasMessage("RETURN_OF_PREMIUM rows are only valid on TERM_LIFE products");
    }

    @Test
    void wholeLifeAnnuityAndUnitLinkedTakeNoRows() {
        // Whole life has no term to mature at; annuities and unit-linked arrive with steps 5 and 6
        // and would need an engine that does not exist.
        for (ProductCategory c : List.of(ProductCategory.WHOLE_LIFE, ProductCategory.ANNUITY, ProductCategory.UNIT_LINKED)) {
            assertThatThrownBy(() -> PayoutPlanValidator.validate(c, PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100))))
                .hasMessage("A " + c + " product cannot carry a payout schedule");
        }
    }

    @Test
    void rowShapeIsChecked() {
        PayoutRowInput survivalNoYears = new PayoutRowInput(PayoutKind.SURVIVAL, null, null,
            PayoutAmountBasis.PERCENT_OF_SA, BigDecimal.TEN, PayoutFrequency.ANNUAL);
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, null, false, null), List.of(MATURITY_100, survivalNoYears))))
            .hasMessage("A SURVIVAL row needs a from and to policy year (from >= 1, to >= from) and a frequency");

        PayoutRowInput maturityWithYears = new PayoutRowInput(PayoutKind.MATURITY, 20, 20,
            PayoutAmountBasis.PERCENT_OF_SA, BigDecimal.TEN, null);
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(maturityWithYears))))
            .hasMessage("A MATURITY row pays on the policy's maturity date and takes no years or frequency");

        PayoutRowInput maturityOffPremiums = new PayoutRowInput(PayoutKind.MATURITY, null, null,
            PayoutAmountBasis.PERCENT_OF_PREMIUMS, BigDecimal.TEN, null);
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(maturityOffPremiums))))
            .hasMessage("Only a RETURN_OF_PREMIUM row is valued as a percent of premiums");

        PayoutRowInput zero = new PayoutRowInput(PayoutKind.MATURITY, null, null,
            PayoutAmountBasis.PERCENT_OF_SA, BigDecimal.ZERO, null);
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(zero))))
            .hasMessage("A payout row's amount must be greater than zero");
    }

    @Test
    void boundsOnTheTermsThemselvesAreChecked() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(400, null, null, null), List.of(MATURITY_100))))
            .hasMessage("A free-look period must be between 1 and 365 days");
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, 99, null, null), List.of(MATURITY_100, INCOME_6_15))))
            .hasMessage("A proof-of-life interval must be between 1 and 60 months");
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, null, null, BigDecimal.ZERO), List.of(MATURITY_100))))
            .hasMessage("A death-benefit premium percent must be greater than 0 and at most 1000");
    }

    // ---- Product step 3: an account version's payouts --------------------------------------

    private static final AccumulationPlan ACCOUNT = new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"),
        BigDecimal.ZERO, List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));

    private static PayoutRowInput accountMaturity(String pct) {
        return new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal(pct), null);
    }

    @Test
    void anAccountVersionsMaturityPaysTheWholeAccount() {
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
            PayoutPlan.authored(FREE_LOOK_15, List.of(accountMaturity("100"))), ACCOUNT))
            .doesNotThrowAnyException();
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(accountMaturity("50"))), ACCOUNT))
            .hasMessage("An account-value maturity pays the whole account (100)");
    }

    @Test
    void anAccountVersionMustNotPayItsMaturityOffTheSumAssured() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(MATURITY_100)), ACCOUNT))
            .hasMessage("An account-based version's maturity pays the account value");
    }

    @Test
    void onlyAnAccountVersionMayPayTheAccountValue() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(accountMaturity("100"))), AccumulationPlan.none()))
            .hasMessage("Only an account-based version can pay the account value");
        // The two-argument form is a SCALE version, so it refuses the same way.
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(FREE_LOOK_15, List.of(accountMaturity("100")))))
            .hasMessage("Only an account-based version can pay the account value");
    }

    @Test
    void anAccountVersionOffersNoSurvivalOrIncomePayouts() {
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT,
                PayoutPlan.authored(new PayoutTerms(15, 12, false, null), List.of(accountMaturity("100"), SURVIVAL_Y5)),
                ACCOUNT))
            .hasMessage("An account-based version pays only its account value; survival and income payouts are not offered");
    }

    @Test
    void aDepositEndowmentCarriesNoScheduleButStillNeedsAFreeLookPeriod() {
        PayoutPlan noRows = PayoutPlan.authored(FREE_LOOK_15, List.of());
        assertThatCode(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT, noRows, AccumulationPlan.forDeposit(), true))
            .doesNotThrowAnyException();
        PayoutPlan noFreeLook = PayoutPlan.authored(new PayoutTerms(null, null, null, null), List.of());
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT, noFreeLook,
                AccumulationPlan.forDeposit(), true))
            .hasMessage("A free-look period in days is required on an individual product");
        // And an ordinary account endowment with no rows is still refused, for the reason it always was.
        assertThatThrownBy(() -> PayoutPlanValidator.validate(ProductCategory.ENDOWMENT, noRows, AccumulationPlan.forDeposit()))
            .hasMessage("An ENDOWMENT product must carry exactly one MATURITY row");
    }
}
