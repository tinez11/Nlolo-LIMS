package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.DepositRateRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The user's grid, and every band boundary the spec names (§7). */
class DepositPlanTest {

    static DepositPlan userGrid() {
        String[][] bands = {{"500000", "3", "4", "5"}, {"6000000", "4", "5", "6"},
                            {"11000000", "5", "6", "7"}, {"21000000", "6", "7", "8"}};
        int[] terms = {3, 6, 12};
        List<DepositRateRow> rows = new ArrayList<>();
        for (String[] band : bands) {
            for (int t = 0; t < terms.length; t++) {
                rows.add(new DepositRateRow(new BigDecimal(band[0]), terms[t], new BigDecimal(band[t + 1])));
            }
        }
        return new DepositPlan(rows);
    }

    private static String rate(String amount, int term) {
        return userGrid().rateFor(new BigDecimal(amount), term).map(BigDecimal::toPlainString).orElse("none");
    }

    @Test
    void everyBoundaryLandsInItsBand() {
        assertThat(rate("499999.99", 3)).isEqualTo("none");
        assertThat(rate("500000", 3)).isEqualTo("3");
        assertThat(rate("5999999", 3)).isEqualTo("3");
        assertThat(rate("5999999.50", 3)).isEqualTo("3");
        assertThat(rate("6000000", 3)).isEqualTo("4");
        assertThat(rate("20999999", 12)).isEqualTo("7");
        assertThat(rate("21000000", 6)).isEqualTo("7");
        assertThat(rate("900000000", 12)).isEqualTo("8");
    }

    @Test
    void aTermTheGridDoesNotOfferHasNoRate() {
        assertThat(rate("1000000", 9)).isEqualTo("none");
    }

    @Test
    void termsAndBandStartsAreSortedAndDistinct() {
        assertThat(userGrid().terms()).containsExactly(3, 6, 12);
        assertThat(userGrid().bandStarts()).extracting(BigDecimal::toPlainString)
            .containsExactly("500000", "6000000", "11000000", "21000000");
    }

    @Test
    void noneIsNotADeposit() {
        assertThat(DepositPlan.none().isDeposit()).isFalse();
        assertThat(userGrid().isDeposit()).isTrue();
    }
}
