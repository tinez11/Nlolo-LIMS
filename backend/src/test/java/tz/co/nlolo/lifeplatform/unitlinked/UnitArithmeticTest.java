package tz.co.nlolo.lifeplatform.unitlinked;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic.Weighted;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The unit ledger's rounding rules (spec §5), in milliseconds. */
class UnitArithmeticTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void allocationRoundsOnceHalfEvenAndTheChargeIsTheRest() {
        var a = UnitArithmetic.allocate(d("100000.00"), d("90"));
        assertThat(a.allocated()).isEqualByComparingTo("90000.00");
        assertThat(a.charge()).isEqualByComparingTo("10000.00");
        var b = UnitArithmetic.allocate(d("33333.33"), d("97.5"));
        assertThat(b.allocated()).isEqualByComparingTo("32500.00"); // 32499.99675 -> 2 dp HALF_EVEN
        assertThat(b.charge()).isEqualByComparingTo("833.33");
        assertThat(b.allocated().add(b.charge())).isEqualByComparingTo("33333.33");
    }

    @Test
    void theSplitAlwaysSumsExactlyAndTheRemainderGoesToTheLargest() {
        var parts = UnitArithmetic.split(d("100.00"), List.of(new Weighted("BD1", d("33")), new Weighted("EQ1", d("67"))));
        assertThat(parts.get(0).add(parts.get(1))).isEqualByComparingTo("100.00");
        assertThat(parts).containsExactly(d("33.00"), d("67.00"));

        var thirds = UnitArithmetic.split(d("100.00"), List.of(
            new Weighted("A", BigDecimal.ONE), new Weighted("B", BigDecimal.ONE), new Weighted("C", BigDecimal.ONE)));
        assertThat(thirds).containsExactly(d("33.34"), d("33.33"), d("33.33")); // a tie: the first by key takes it
        assertThat(thirds.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("100.00");
    }

    @Test
    void boughtUnitsTruncateSoTheCustomerNeverGetsMoreThanTheMoneyBuys() {
        assertThat(UnitArithmetic.unitsBought(d("100.00"), d("3.000000"))).isEqualByComparingTo("33.333333");
        assertThat(UnitArithmetic.proceeds(UnitArithmetic.unitsBought(d("100.00"), d("3.000000")), d("3.000000")))
            .isLessThanOrEqualTo(d("100.00"));
    }

    @Test
    void soldUnitsCeilToCoverTheMoneyButNeverExceedTheHolding() {
        assertThat(UnitArithmetic.unitsToSell(d("100.00"), d("3.000000"), d("1000"))).isEqualByComparingTo("33.333334");
        assertThat(UnitArithmetic.unitsToSell(d("100.00"), d("3.000000"), d("10"))).isEqualByComparingTo("10");
    }

    @Test
    void proceedsRoundHalfEven() {
        assertThat(UnitArithmetic.proceeds(d("33.333333"), d("3.000000"))).isEqualByComparingTo("100.00");
        assertThat(UnitArithmetic.proceeds(d("0.000001"), d("1.000000"))).isEqualByComparingTo("0.00");
    }

    @Test
    void aZeroOrMissingPriceIsRefusedNeverDividedBy() {
        assertThatThrownBy(() -> UnitArithmetic.unitsBought(BigDecimal.TEN, BigDecimal.ZERO))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("never defaulted");
        assertThatThrownBy(() -> UnitArithmetic.proceeds(BigDecimal.TEN, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
