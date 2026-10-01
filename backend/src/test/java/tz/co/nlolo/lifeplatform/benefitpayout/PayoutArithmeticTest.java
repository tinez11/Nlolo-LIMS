package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.PayoutArithmetic;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every money figure the payout engine computes, and the one property that matters most: a year's
 * instalments must add back up to the year's amount exactly. A rounding remainder dropped twelve
 * times is a customer short-paid, and nothing downstream would ever notice.
 */
class PayoutArithmeticTest {

    @Test
    void splitPutsTheRemainderOnTheLastPartSoTheYearTotalIsExact() {
        assertThat(PayoutArithmetic.splitYear(new BigDecimal("100.00"), 3))
            .containsExactly(new BigDecimal("33.33"), new BigDecimal("33.33"), new BigDecimal("33.34"));
    }

    @Test
    void anEvenlyDivisibleYearSplitsEvenly() {
        assertThat(PayoutArithmetic.splitYear(new BigDecimal("1200.00"), 12))
            .allMatch(p -> p.compareTo(new BigDecimal("100.00")) == 0);
    }

    @Test
    void everySplitSumsBackToTheYearAmount() {
        // The property, over the frequencies the platform actually offers and some awkward figures.
        for (String amount : new String[] { "100.00", "1000.00", "333.33", "0.07", "99999.99" }) {
            for (int parts : new int[] { 1, 2, 4, 12 }) {
                BigDecimal year = new BigDecimal(amount);
                BigDecimal summed = PayoutArithmetic.splitYear(year, parts).stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                assertThat(summed)
                    .as("%s split %d ways", amount, parts)
                    .isEqualByComparingTo(year);
            }
        }
    }

    @Test
    void percentOfRoundsHalfEvenToCents() {
        assertThat(PayoutArithmetic.percentOf(new BigDecimal("1000000.00"), new BigDecimal("3")))
            .isEqualByComparingTo("30000.00");
        assertThat(PayoutArithmetic.percentOf(new BigDecimal("333.33"), new BigDecimal("50")))
            .isEqualByComparingTo("166.66");
    }

    @Test
    void restateScalesByPaidUpOverOriginal() {
        // Step 1's PROPORTIONATE basis: 400,000 of a 1,000,000 sum assured is two fifths of it.
        assertThat(PayoutArithmetic.restate(new BigDecimal("100000.00"), new BigDecimal("400000.00"),
            new BigDecimal("1000000.00"))).isEqualByComparingTo("40000.00");
    }
}
