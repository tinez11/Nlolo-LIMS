package tz.co.nlolo.lifeplatform.annuity;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.annuity.domain.CommutationSplit;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** A pension's balance split into its lump sum and its purchase price (product step 5 D2). */
class CommutationSplitTest {

    @Test
    void theLumpSumIsRoundedOnceAndThePriceIsTheRest() {
        var s = CommutationSplit.of(new BigDecimal("48123456.79"), new BigDecimal("25"));
        assertThat(s.lumpSum()).isEqualByComparingTo("12030864.20");      // 12,030,864.1975 -> .20
        assertThat(s.purchasePrice()).isEqualByComparingTo("36092592.59");
        assertThat(s.lumpSum().add(s.purchasePrice())).isEqualByComparingTo("48123456.79");
    }

    @Test
    void halfACentRoundsToEven() {
        // 0.10 x 25% = 0.025 -> 0.02 (HALF_EVEN), not 0.03.
        var s = CommutationSplit.of(new BigDecimal("0.10"), new BigDecimal("25"));
        assertThat(s.lumpSum()).isEqualByComparingTo("0.02");
        assertThat(s.purchasePrice()).isEqualByComparingTo("0.08");
    }

    @Test
    void noLumpSumBuysWithTheWholeBalance() {
        var s = CommutationSplit.of(new BigDecimal("1000.00"), BigDecimal.ZERO);
        assertThat(s.lumpSum()).isEqualByComparingTo("0.00");
        assertThat(s.purchasePrice()).isEqualByComparingTo("1000.00");
    }
}
