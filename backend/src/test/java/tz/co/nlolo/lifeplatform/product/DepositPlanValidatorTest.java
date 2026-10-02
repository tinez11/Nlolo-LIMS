package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.DepositPlanValidator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DepositPlanValidatorTest {

    private static final PayoutPlan NO_ROWS = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of());

    private static void check(ProductCategory category, DepositPlan plan) {
        DepositPlanValidator.validate(category, plan, AccumulationPlan.none(), CashValuePlan.none(),
            FrequencyLoading.none(), NO_ROWS, EligibilityBounds.none());
    }

    private static DepositPlan with(DepositRateRow... extra) {
        List<DepositRateRow> rows = new ArrayList<>(DepositPlanTest.userGrid().rows());
        rows.addAll(List.of(extra));
        return new DepositPlan(rows);
    }

    @Test
    void theUsersGridIsAccepted() {
        assertThatCode(() -> check(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid())).doesNotThrowAnyException();
    }

    @Test
    void noneIsNotChecked() {
        assertThatCode(() -> check(ProductCategory.TERM_LIFE, DepositPlan.none())).doesNotThrowAnyException();
    }

    @Test
    void aProtectionCategoryCannotBeADeposit() {
        assertThatThrownBy(() -> check(ProductCategory.TERM_LIFE, DepositPlanTest.userGrid()))
            .hasMessage("A TERM_LIFE product cannot be a fixed-term deposit");
    }

    @Test
    void aSavingsAccountBlockAlongsideIsRefused() {
        AccumulationPlan account = new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ONE, BigDecimal.ZERO,
            List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO)));
        assertThatThrownBy(() -> DepositPlanValidator.validate(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid(),
                account, CashValuePlan.none(), FrequencyLoading.none(), NO_ROWS, EligibilityBounds.none()))
            .hasMessage("A fixed-term deposit sets its own account terms; send no savings-account block with it");
    }

    @Test
    void aFrequencyLoadingIsRefused() {
        assertThatThrownBy(() -> DepositPlanValidator.validate(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid(),
                AccumulationPlan.none(), CashValuePlan.none(), new FrequencyLoading(new BigDecimal("5"), BigDecimal.ZERO),
                NO_ROWS, EligibilityBounds.none()))
            .hasMessage("A fixed-term deposit is paid once; it takes no frequency loading");
    }

    @Test
    void aPayoutRowIsRefused() {
        PayoutPlan withRow = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
            PayoutKind.MATURITY, null, null, PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal("100"), null)));
        assertThatThrownBy(() -> DepositPlanValidator.validate(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid(),
                AccumulationPlan.none(), CashValuePlan.none(), FrequencyLoading.none(), withRow, EligibilityBounds.none()))
            .hasMessage("A fixed-term deposit matures through its account; it carries no payout schedule");
    }

    @Test
    void aDuplicateCellIsRefused() {
        assertThatThrownBy(() -> check(ProductCategory.ENDOWMENT,
                with(new DepositRateRow(new BigDecimal("500000"), 3, new BigDecimal("9")))))
            .hasMessage("The deposit rate grid has two rates for deposits from 500000 over 3 months");
    }

    @Test
    void aBandMissingATermIsRefused() {
        assertThatThrownBy(() -> check(ProductCategory.ENDOWMENT,
                with(new DepositRateRow(new BigDecimal("50000000"), 3, new BigDecimal("9")))))
            .hasMessage("The band from 50000000 does not offer a 6-month term");
    }

    @Test
    void aRateOutsideZeroToHundredIsRefused() {
        assertThatThrownBy(() -> check(ProductCategory.ENDOWMENT, new DepositPlan(List.of(
                new DepositRateRow(new BigDecimal("500000"), 3, new BigDecimal("101"))))))
            .hasMessage("A deposit rate must be between 0 and 100 percent");
    }

    @Test
    void aTermOutsideOneToHundredTwentyMonthsIsRefused() {
        assertThatThrownBy(() -> check(ProductCategory.ENDOWMENT, new DepositPlan(List.of(
                new DepositRateRow(new BigDecimal("500000"), 0, new BigDecimal("3"))))))
            .hasMessage("A deposit term must be between 1 and 120 months");
    }

    @Test
    void theLowestBandMustStartAtTheMinimumSumAssured() {
        assertThatThrownBy(() -> DepositPlanValidator.validate(ProductCategory.ENDOWMENT, DepositPlanTest.userGrid(),
                AccumulationPlan.none(), CashValuePlan.none(), FrequencyLoading.none(), NO_ROWS,
                new EligibilityBounds(null, null, null, null, new BigDecimal("1000000"), null)))
            .hasMessage("The lowest deposit band must start at the version's minimum sum assured (1000000)");
    }
}
