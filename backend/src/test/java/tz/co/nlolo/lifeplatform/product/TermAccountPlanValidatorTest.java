package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.*;
import tz.co.nlolo.lifeplatform.product.domain.TermAccountPlanValidator;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Everything a CHECK cannot say about a term account (2026-10-21). */
class TermAccountPlanValidatorTest {

    private static final List<AccumulationChargeRow> CHARGES =
        List.of(new AccumulationChargeRow(1, null, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
    private static final EligibilityBounds TERMED = new EligibilityBounds(null, null, 12, 60, null, null);
    private static final PayoutPlan NO_ROWS = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of());

    private static AccumulationPlan band(String min, String max) {
        return new AccumulationPlan(ValueBasis.ACCOUNT, BigDecimal.ZERO, BigDecimal.ZERO, CHARGES,
            min == null ? null : new BigDecimal(min), max == null ? null : new BigDecimal(max));
    }

    private static void refused(AccumulationPlan plan, PayoutPlan payout, EligibilityBounds bounds, String message) {
        assertThatThrownBy(() -> TermAccountPlanValidator.validate(plan, payout, bounds))
            .isInstanceOf(InvalidProductVersionException.class).hasMessageContaining(message);
    }

    @Test
    void anOrderedBandWithBothTermBoundsAndNoPayoutRowsIsATermAccount() {
        assertThatCode(() -> TermAccountPlanValidator.validate(band("3", "7"), NO_ROWS, TERMED)).doesNotThrowAnyException();
        assertThatCode(() -> TermAccountPlanValidator.validate(band("5", "5"), NO_ROWS, TERMED)).doesNotThrowAnyException();
        assertThat(band("3", "7").isTermAccount()).isTrue();
        assertThat(band("3", "7").offers(new BigDecimal("7.00"))).isTrue();
        assertThat(band("3", "7").offers(new BigDecimal("7.01"))).isFalse();
    }

    @Test
    void anOrdinaryAccountAndAScaleVersionAreLeftAlone() {
        assertThatCode(() -> TermAccountPlanValidator.validate(band(null, null), NO_ROWS, EligibilityBounds.none()))
            .doesNotThrowAnyException();
        assertThatCode(() -> TermAccountPlanValidator.validate(AccumulationPlan.none(), NO_ROWS, EligibilityBounds.none()))
            .doesNotThrowAnyException();
        assertThat(band(null, null).isTermAccount()).isFalse();
        assertThat(AccumulationPlan.forDeposit().isTermAccount()).isFalse();
    }

    @Test
    void halfABandIsRefused() {
        refused(band("3", null), NO_ROWS, TERMED, "both a minimum and a maximum rate");
        refused(band(null, "7"), NO_ROWS, TERMED, "both a minimum and a maximum rate");
    }

    @Test
    void aMaximumBelowTheMinimumIsRefused() {
        refused(band("7", "3"), NO_ROWS, TERMED, "maximum rate cannot be below its minimum");
    }

    @Test
    void aRateOutsideZeroToAHundredIsRefused() {
        refused(band("-1", "7"), NO_ROWS, TERMED, "between 0 and 100 percent");
        refused(band("3", "101"), NO_ROWS, TERMED, "between 0 and 100 percent");
    }

    @Test
    void bothTermBoundsAreRequired() {
        refused(band("3", "7"), NO_ROWS, EligibilityBounds.none(), "minimum and a maximum term");
        refused(band("3", "7"), NO_ROWS, new EligibilityBounds(null, null, 12, null, null, null), "minimum and a maximum term");
    }

    @Test
    void aPayoutScheduleIsRefused() {
        PayoutPlan maturity = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
            PayoutKind.MATURITY, null, null, PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal("100"), null)));
        refused(band("3", "7"), maturity, TERMED, "carries no payout schedule");
    }
}
