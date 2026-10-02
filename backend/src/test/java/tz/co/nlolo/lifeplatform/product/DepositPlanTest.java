package tz.co.nlolo.lifeplatform.product;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.DepositPlan;
import tz.co.nlolo.lifeplatform.product.api.DepositRateRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The user's grid, and every band boundary. Their bands were 500,000-5,000,000, 6,000,000-10,000,000,
 * 11,000,000-20,000,000 and 21,000,000+, with the gaps between them answered (2026-10-02): an amount
 * above a band's top goes to the HIGHER band. So each band past the first starts one cent above the
 * previous band's top -- 5,000,000 is 3%, 5,000,000.01 and 5,500,000 are 4%.
 */
class DepositPlanTest {

    static DepositPlan userGrid() {
        String[][] bands = {{"500000", "3", "4", "5"}, {"5000000.01", "4", "5", "6"},
                            {"10000000.01", "5", "6", "7"}, {"20000000.01", "6", "7", "8"}};
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
        // The user's own figures, each in the band they wrote it in.
        assertThat(rate("500000", 3)).isEqualTo("3");
        assertThat(rate("5000000", 3)).isEqualTo("3");
        assertThat(rate("6000000", 3)).isEqualTo("4");
        assertThat(rate("10000000", 12)).isEqualTo("6");
        assertThat(rate("11000000", 3)).isEqualTo("5");
        assertThat(rate("20000000", 12)).isEqualTo("7");
        assertThat(rate("21000000", 6)).isEqualTo("7");
        assertThat(rate("900000000", 12)).isEqualTo("8");
        // Between their bands: the HIGHER band (their answer, 2026-10-02).
        assertThat(rate("5000000.01", 3)).isEqualTo("4");
        assertThat(rate("5500000", 3)).isEqualTo("4");
        assertThat(rate("10500000", 6)).isEqualTo("6");
        assertThat(rate("20500000", 12)).isEqualTo("8");
    }

    @Test
    void aTermTheGridDoesNotOfferHasNoRate() {
        assertThat(rate("1000000", 9)).isEqualTo("none");
    }

    @Test
    void termsAndBandStartsAreSortedAndDistinct() {
        assertThat(userGrid().terms()).containsExactly(3, 6, 12);
        assertThat(userGrid().bandStarts()).extracting(BigDecimal::toPlainString)
            .containsExactly("500000", "5000000.01", "10000000.01", "20000000.01");
    }

    @Test
    void noneIsNotADeposit() {
        assertThat(DepositPlan.none().isDeposit()).isFalse();
        assertThat(userGrid().isDeposit()).isTrue();
    }
}
