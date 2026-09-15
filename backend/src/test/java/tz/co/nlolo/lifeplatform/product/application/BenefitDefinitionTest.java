package tz.co.nlolo.lifeplatform.product.application;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;
import tz.co.nlolo.lifeplatform.product.api.BenefitDefinition;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a benefit actually pays, without a Spring context.
 *
 * <p>This is the number a claim is valued at, and until now there was no such number: every claim
 * type resolved to the policy's full sum assured, so a critical-illness claim paid a death benefit.
 */
class BenefitDefinitionTest {

    private static final BigDecimal COVER = new BigDecimal("10000000");

    @Test
    void sumAssuredPaysTheWholeCover() {
        BenefitDefinition death = new BenefitDefinition(
            BenefitType.DEATH, BenefitCalculationMethod.SUM_ASSURED, null, null);
        assertThat(death.amountFor(COVER)).isEqualByComparingTo(COVER);
    }

    @Test
    void aPercentageBenefitPaysItsFraction() {
        BenefitDefinition ci = new BenefitDefinition(BenefitType.CRITICAL_ILLNESS,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25"), null);
        assertThat(ci.amountFor(COVER)).isEqualByComparingTo(new BigDecimal("2500000.00"));
    }

    @Test
    void aFlatBenefitIgnoresTheCover() {
        BenefitDefinition funeral = new BenefitDefinition(BenefitType.DEATH,
            BenefitCalculationMethod.FLAT_AMOUNT, null, new BigDecimal("500000"));
        assertThat(funeral.amountFor(COVER)).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(funeral.amountFor(new BigDecimal("1"))).isEqualByComparingTo(new BigDecimal("500000"));
    }

    @Test
    void aPercentageRoundsOnceToTwoDecimals() {
        BenefitDefinition third = new BenefitDefinition(BenefitType.DISABILITY,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("33.33"), null);
        // 3 x 33.33% = 0.9999, which rounds HALF_UP to 1.00 -- once, at the end.
        assertThat(third.amountFor(new BigDecimal("3"))).isEqualByComparingTo(new BigDecimal("1.00"));
    }

    @Test
    void aPercentageBenefitNeedsAPercentAndNothingElse() {
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.CRITICAL_ILLNESS,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("percentage");
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.CRITICAL_ILLNESS,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("25"), new BigDecimal("1")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("flat amount");
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.CRITICAL_ILLNESS,
            BenefitCalculationMethod.PERCENTAGE_OF_SUM_ASSURED, new BigDecimal("101"), null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
    }

    @Test
    void aFlatBenefitNeedsAnAmountAndNothingElse() {
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.DEATH,
            BenefitCalculationMethod.FLAT_AMOUNT, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("flat amount");
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.DEATH,
            BenefitCalculationMethod.FLAT_AMOUNT, null, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("greater than zero");
    }

    @Test
    void sumAssuredCarriesNeitherAmount() {
        assertThatThrownBy(() -> new BenefitDefinition(BenefitType.DEATH,
            BenefitCalculationMethod.SUM_ASSURED, new BigDecimal("25"), null))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
