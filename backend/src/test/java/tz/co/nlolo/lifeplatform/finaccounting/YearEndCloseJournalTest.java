package tz.co.nlolo.lifeplatform.finaccounting;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.finaccounting.domain.YearEndCloseJournal;
import tz.co.nlolo.lifeplatform.finaccounting.domain.YearEndCloseJournal.Balance;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** IFRS 17 I6: classes 4-8 closed to 3310, M-07 to 3210, M-11 for dividends -- the guide's 5.7, balanced. */
class YearEndCloseJournalTest {

    private static String text(YearEndCloseJournal.Line l) {
        return l.side() + " " + l.account() + " " + l.amount();
    }

    private static BigDecimal side(YearEndCloseJournal.Result r, String side) {
        return r.lines().stream().filter(l -> l.side().equals(side)).map(YearEndCloseJournal.Line::amount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void aProfitClosesToRetainedEarningsThroughCurrentYearProfit() {
        var r = YearEndCloseJournal.build(List.of(new Balance("4110", new BigDecimal("-1000.00")),
            new Balance("5110", new BigDecimal("300.00")), new Balance("8110", new BigDecimal("100.00"))),
            BigDecimal.ZERO);
        assertThat(r.profit()).isEqualByComparingTo("600.00");
        assertThat(r.classTotals()).containsEntry("4", new BigDecimal("-1000.00"))
            .containsEntry("8", new BigDecimal("100.00"));
        assertThat(r.lines()).extracting(YearEndCloseJournalTest::text).containsExactly(
            "DR 4110 1000.00", "CR 5110 300.00", "CR 8110 100.00", "CR 3310 600.00",
            "DR 3310 600.00", "CR 3210 600.00");
        assertThat(side(r, "DR")).isEqualByComparingTo(side(r, "CR"));
    }

    @Test
    void aLossMovesRetainedEarningsTheOtherWay() {
        var r = YearEndCloseJournal.build(List.of(new Balance("8110", new BigDecimal("250.00"))), BigDecimal.ZERO);
        assertThat(r.profit()).isEqualByComparingTo("-250.00");
        assertThat(r.lines()).extracting(YearEndCloseJournalTest::text).containsExactly(
            "CR 8110 250.00", "DR 3310 250.00", "DR 3210 250.00", "CR 3310 250.00");
        assertThat(side(r, "DR")).isEqualByComparingTo(side(r, "CR"));
    }

    @Test
    void dividendsDeclaredCloseToRetainedEarningsAndZeroAccountsAreSkipped() {
        var r = YearEndCloseJournal.build(List.of(new Balance("4110", new BigDecimal("-500.00")),
            new Balance("7110", BigDecimal.ZERO)), new BigDecimal("200.00"));
        assertThat(r.dividends()).isEqualByComparingTo("200.00");
        assertThat(r.lines()).extracting(YearEndCloseJournalTest::text).containsExactly(
            "DR 4110 500.00", "CR 3310 500.00", "DR 3310 500.00", "CR 3210 500.00", "DR 3210 200.00", "CR 3320 200.00");
        assertThat(side(r, "DR")).isEqualByComparingTo(side(r, "CR"));
    }

    @Test
    void nothingToCloseIsNoLines() {
        assertThat(YearEndCloseJournal.build(List.of(), BigDecimal.ZERO).lines()).isEmpty();
        assertThat(YearEndCloseJournal.build(List.of(), new BigDecimal("10.00")).lines())
            .as("dividends alone still close").hasSize(2);
    }
}
