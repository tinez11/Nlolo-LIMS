package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.AccumulationPlanValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** AccumulationPlanValidator, rule by rule, in the exact words the console mirrors. */
class AccumulationPlanValidatorTest {

    private static AccumulationChargeRow row(int from, Integer to) {
        return new AccumulationChargeRow(from, to, new BigDecimal("5"), BigDecimal.ZERO, new BigDecimal("1000"));
    }

    private static AccumulationPlan account(List<AccumulationChargeRow> charges) {
        return new AccumulationPlan(ValueBasis.ACCOUNT, new BigDecimal("3"), new BigDecimal("50000"), charges);
    }

    private static final CashValuePlan SCALE = new CashValuePlan("ACT/1", LocalDate.of(2026, 1, 1), "PROPORTIONATE", 2,
        List.of(new CashValueRowInput(2, null, null, new BigDecimal("200"), null)));

    @Test
    void aScaleVersionIsNotChecked() {
        assertThatCode(() -> AccumulationPlanValidator.validate(ProductCategory.TERM_LIFE, AccumulationPlan.none(),
            CashValuePlan.none())).doesNotThrowAnyException();
    }

    @Test
    void acceptsAWellFormedAccountVersion() {
        assertThatCode(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
            account(List.of(row(1, 2), row(3, null))), CashValuePlan.none())).doesNotThrowAnyException();
    }

    @Test
    void refusesAnAccountBasisOnACategoryThatCannotCarryOne() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ANNUITY,
                account(List.of(row(1, null))), CashValuePlan.none()))
            .hasMessage("A ANNUITY product cannot use an account value basis");
    }

    @Test
    void aDeferredAnnuityMayUseAnAccountAndAnImmediateOneStillMayNot() {
        assertThatCode(() -> AccumulationPlanValidator.validate(ProductCategory.ANNUITY,
            account(List.of(row(1, null))), CashValuePlan.none(), true)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ANNUITY,
                account(List.of(row(1, null))), CashValuePlan.none(), false))
            .hasMessage("A ANNUITY product cannot use an account value basis");
    }

    @Test
    void refusesAScaleAndAnAccountTogether() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(1, null))), SCALE))
            .hasMessage("A version is valued either by a cash-value scale or by an account, not both");
    }

    @Test
    void refusesAMissingOrOutOfRangeGuarantee() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                new AccumulationPlan(ValueBasis.ACCOUNT, null, BigDecimal.ZERO, List.of(row(1, null))), CashValuePlan.none()))
            .hasMessage("An account-based version needs a guaranteed interest rate between 0 and 100 percent");
    }

    @Test
    void refusesAMissingMinimumBalance() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ONE, null, List.of(row(1, null))), CashValuePlan.none()))
            .hasMessage("An account-based version needs a minimum balance for withdrawals, zero or more");
    }

    @Test
    void chargesMustStartAtYearOneAndCoverEveryYearAfter() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(2, null))), CashValuePlan.none()))
            .hasMessage("Account charges must start at policy year 1");
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(1, 2), row(4, null))), CashValuePlan.none()))
            .hasMessage("Account charges leave policy year 3 uncovered");
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(1, 3), row(2, null))), CashValuePlan.none()))
            .hasMessage("Account charges for policy years 1-3 and 2 onwards overlap");
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(row(1, 2))), CashValuePlan.none()))
            .hasMessage("The last account charge row must be open-ended, so every policy year has a charge");
    }

    @Test
    void refusesAPercentOutsideZeroToAHundredOrANegativeFee() {
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(new AccumulationChargeRow(1, null, new BigDecimal("101"), BigDecimal.ZERO, BigDecimal.ZERO))),
                CashValuePlan.none()))
            .hasMessage("An allocation charge must be between 0 and 100 percent");
        assertThatThrownBy(() -> AccumulationPlanValidator.validate(ProductCategory.ENDOWMENT,
                account(List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("-1")))),
                CashValuePlan.none()))
            .hasMessage("A monthly policy fee cannot be negative");
    }
}
