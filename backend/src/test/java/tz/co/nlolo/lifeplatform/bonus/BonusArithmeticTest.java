package tz.co.nlolo.lifeplatform.bonus;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.bonus.domain.BonusArithmetic;
import tz.co.nlolo.lifeplatform.product.api.BonusMethod;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BonusArithmeticTest {

    private static final BigDecimal SA = new BigDecimal("1000000.00");

    @Test
    void simpleIsAlwaysOnTheSumAssured() {
        assertThat(BonusArithmetic.reversionary(BonusMethod.SIMPLE, SA, new BigDecimal("60000"), new BigDecimal("3")))
            .isEqualByComparingTo("30000.00");
    }

    @Test
    void compoundEarnsOnEarlierBonusesOverThreeDeclarations() {
        BigDecimal attached = BigDecimal.ZERO;
        for (int year = 0; year < 3; year++) {
            attached = attached.add(BonusArithmetic.reversionary(BonusMethod.COMPOUND, SA, attached, new BigDecimal("3")));
        }
        // 30,000 then 30,900 then 31,827.00 -- 92,727.00, against simple's 90,000.
        assertThat(attached).isEqualByComparingTo("92727.00");
    }

    @Test
    void roundsOnceHalfEven() {
        // 1,000,000.50 x 2.5% = 25,000.0125 -> 25,000.01
        assertThat(BonusArithmetic.reversionary(BonusMethod.SIMPLE, new BigDecimal("1000000.50"), BigDecimal.ZERO,
            new BigDecimal("2.5"))).isEqualByComparingTo("25000.01");
        // 0.125 -> 0.12 (half-even), not 0.13
        assertThat(BonusArithmetic.terminal(new BigDecimal("0.25"), new BigDecimal("50"))).isEqualByComparingTo("0.12");
    }

    @Test
    void interimIsTheRateForTheWholeMonthsOnTheMethodsBase() {
        // compound: (1,000,000 + 60,000) x 3% x 7/12 = 18,550.00
        assertThat(BonusArithmetic.interim(BonusMethod.COMPOUND, SA, new BigDecimal("60000"), new BigDecimal("3"), 7))
            .isEqualByComparingTo("18550.00");
        assertThat(BonusArithmetic.interim(BonusMethod.SIMPLE, SA, new BigDecimal("60000"), new BigDecimal("3"), 0))
            .isEqualByComparingTo("0.00");
    }

    @Test
    void terminalIsAPercentOfAttachedBonuses() {
        assertThat(BonusArithmetic.terminal(new BigDecimal("92727.00"), new BigDecimal("150"))).isEqualByComparingTo("139090.50");
    }

    @Test
    void wholeMonthsCountsOnlyCompletedMonthsAndNeverGoesNegative() {
        assertThat(BonusArithmetic.wholeMonths(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 7, 30))).isEqualTo(6);
        assertThat(BonusArithmetic.wholeMonths(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 7, 31))).isEqualTo(7);
        assertThat(BonusArithmetic.wholeMonths(LocalDate.of(2027, 1, 1), LocalDate.of(2026, 12, 31))).isZero();
    }
}
