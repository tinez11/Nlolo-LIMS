package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.CashValuePlan;
import tz.co.nlolo.lifeplatform.product.api.CashValueRowInput;
import tz.co.nlolo.lifeplatform.product.api.InvalidProductVersionException;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;
import tz.co.nlolo.lifeplatform.product.domain.CashValuePlanValidator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CashValuePlanValidatorTest {

    private static final LocalDate BASIS_DATE = LocalDate.of(2026, 1, 1);
    private static final List<CashValueRowInput> TWO_YEARS = List.of(
        new CashValueRowInput(2, null, null, new BigDecimal("200"), null),
        new CashValueRowInput(3, null, null, new BigDecimal("300"), null));

    private static CashValuePlan plan(String reference, String basis, Integer minYears, List<CashValueRowInput> rows) {
        return new CashValuePlan(reference, reference == null ? null : BASIS_DATE, basis, minYears, rows);
    }

    @Test
    void noPlanIsAlwaysAccepted() {
        assertThatCode(() -> CashValuePlanValidator.validate(ProductCategory.TERM_LIFE, CashValuePlan.none()))
            .doesNotThrowAnyException();
    }

    @Test
    void aSignedProportionateTableOnAnEndowmentIsAccepted() {
        assertThatCode(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
            plan("ACT-2026-01", "PROPORTIONATE", 2, TWO_YEARS))).doesNotThrowAnyException();
    }

    @Test
    void pureProtectionCannotCarryACashValue() {
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.TERM_LIFE,
                plan("ACT-2026-01", "PROPORTIONATE", 2, TWO_YEARS)))
            .isInstanceOf(InvalidProductVersionException.class)
            .hasMessage("A TERM_LIFE product cannot carry a cash-value table");
    }

    @Test
    void aTableNeedsTheActuarialSignOff() {
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.WHOLE_LIFE,
                plan(null, "PROPORTIONATE", 2, TWO_YEARS)))
            .hasMessage("A cash-value table needs the actuarial basis reference and date it was signed off under");
    }

    @Test
    void minimumYearsAreRequiredAndTwoOrThree() {
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "PROPORTIONATE", null, TWO_YEARS)))
            .hasMessage("A cash-value table needs the minimum years before any value exists, 2 or 3");
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "PROPORTIONATE", 5, TWO_YEARS)))
            .hasMessage("A cash-value table needs the minimum years before any value exists, 2 or 3");
    }

    @Test
    void theBasisIsOneOfTwoAndATableBasisNeedsPaidUpValues() {
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "GUESS", 2, TWO_YEARS)))
            .hasMessage("The paid-up basis must be PROPORTIONATE or TABLE");
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "TABLE", 2, TWO_YEARS)))
            .hasMessage("A TABLE paid-up basis needs a paid-up value on every row (policy year 2 has none)");
    }

    @Test
    void aTableNeedsRowsAndRowsMustNotOverlap() {
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "PROPORTIONATE", 2, List.of())))
            .hasMessage("A cash-value table needs at least one row");
        List<CashValueRowInput> overlapping = List.of(
            new CashValueRowInput(2, 18, 40, new BigDecimal("200"), null),
            new CashValueRowInput(2, 35, 60, new BigDecimal("180"), null));
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "PROPORTIONATE", 2, overlapping)))
            .hasMessage("Cash-value rows for policy year 2, ages 18-40 and 35-60, overlap -- an entry age in both"
                + " would be valued differently depending on row order");
    }

    @Test
    void rowShapeIsChecked() {
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "PROPORTIONATE", 2,
                    List.of(new CashValueRowInput(0, null, null, BigDecimal.TEN, null)))))
            .hasMessage("A cash-value row's policy year must be 1 or more");
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "PROPORTIONATE", 2,
                    List.of(new CashValueRowInput(2, 40, null, BigDecimal.TEN, null)))))
            .hasMessage("A cash-value age band needs both a from and a to age, or neither");
        assertThatThrownBy(() -> CashValuePlanValidator.validate(ProductCategory.ENDOWMENT,
                plan("ACT-2026-01", "PROPORTIONATE", 2,
                    List.of(new CashValueRowInput(2, null, null, new BigDecimal("-1"), null)))))
            .hasMessage("A cash value per 1,000 cannot be negative");
    }
}
