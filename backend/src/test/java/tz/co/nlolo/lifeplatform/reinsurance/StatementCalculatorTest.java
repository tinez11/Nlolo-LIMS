package tz.co.nlolo.lifeplatform.reinsurance;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Figures;
import tz.co.nlolo.lifeplatform.reinsurance.domain.StatementCalculator.Quarter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** IFRS 17 I3d: the quarterly statement's arithmetic -- the guide's R-01, R-03 and R-04, and its calendar. */
class StatementCalculatorTest {

    private static BigDecimal d(String s) { return new BigDecimal(s); }

    private static String describe(StatementCalculator.JournalLine l) {
        return l.entry() + " " + l.side() + " " + l.account() + " " + l.amount().toPlainString();
    }

    /** The guide's R-01 example: premium 1,800,000, commission 360,000, recoveries 30,000,000 -- the reinsurer owes us. */
    @Test
    void theGuidesExampleNetsToTheReinsurerOwingUs() {
        Figures f = new Figures(d("1800000.00"), d("360000.00"), d("30000000.00"), d("0.00"), d("0.00"));
        assertThat(f.owedToUs()).isEqualByComparingTo("28560000.00");
        assertThat(f.owedByUs()).isZero();
        assertThat(f.journal()).extracting(StatementCalculatorTest::describe)
            .containsExactly("R-01 DR 1430 1800000.00", "R-01 CR 1431 360000.00", "R-01 CR 1420 30000000.00",
                "R-01 DR 1434 28560000.00");
    }

    @Test
    void premiumAboveRecoveriesAndCommissionMeansWeOweTheReinsurer() {
        Figures f = new Figures(d("500000.00"), d("50000.00"), d("100000.00"), d("0.00"), d("0.00"));
        assertThat(f.owedByUs()).isEqualByComparingTo("350000.00");
        assertThat(f.owedToUs()).isZero();
        assertThat(f.journal()).extracting(l -> l.side() + " " + l.account())
            .containsExactly("DR 1430", "CR 1431", "CR 1420", "CR 1434");
    }

    @Test
    void fundsWithheldAndProfitCommissionPostTheirOwnEntriesAndTheJournalBalances() {
        Figures f = new Figures(d("1000000.00"), d("0.00"), d("0.00"), d("200000.00"), d("75000.00"));
        assertThat(f.journal()).extracting(StatementCalculatorTest::describe)
            .containsExactly("R-04 DR 1430 200000.00", "R-04 CR 2550 200000.00", "R-01 DR 1430 800000.00",
                "R-01 CR 1434 800000.00", "R-03 DR 1433 75000.00", "R-03 CR 6120 75000.00");
        BigDecimal dr = f.journal().stream().filter(l -> l.side().equals("DR"))
            .map(StatementCalculator.JournalLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cr = f.journal().stream().filter(l -> l.side().equals("CR"))
            .map(StatementCalculator.JournalLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(dr).isEqualByComparingTo(cr);
    }

    @Test
    void zeroLinesAreLeftOutAndAQuietQuarterPostsNothing() {
        assertThat(new Figures(d("0.00"), d("0.00"), d("0.00"), d("0.00"), d("0.00")).journal()).isEmpty();
    }

    @Test
    void fundsWithheldCannotExceedThePremiumAndNothingIsNegative() {
        assertThat(new Figures(d("100.00"), d("0.00"), d("0.00"), d("100.01"), d("0.00")).problems())
            .containsExactly("Funds withheld (100.01) cannot exceed the quarter's premium payable (100.00)");
        assertThat(new Figures(d("100.00"), d("0.00"), d("0.00"), d("0.00"), d("-1.00")).problems())
            .containsExactly("Profit commission cannot be negative");
        assertThat(new Figures(d("100.00"), d("0.00"), d("0.00"), d("0.001"), d("0.00")).problems())
            .containsExactly("Funds withheld has at most two decimal places");
        assertThat(new Figures(d("100.00"), d("0.00"), d("0.00"), d("100.00"), d("0.00")).problems()).isEmpty();
    }

    @Test
    void aQuarterHasItsMonthsAndEndsWhenTheNextBegins() {
        Quarter q3 = Quarter.parse("2026-Q3");
        assertThat(q3.months()).containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8), YearMonth.of(2026, 9));
        assertThat(q3.hasEnded(LocalDate.of(2026, 9, 30))).isFalse();
        assertThat(q3.hasEnded(LocalDate.of(2026, 10, 1))).isTrue();
        assertThat(q3.toString()).isEqualTo("2026-Q3");
        assertThat(Quarter.of(LocalDate.of(2026, 11, 5)).toString()).isEqualTo("2026-Q4");
        assertThat(Quarter.parse("2026-Q4").endExclusive()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThatThrownBy(() -> Quarter.parse("2026-Q5")).hasMessageContaining("YYYY-Qn");
    }

    @Test
    void onlyMonthsInsideTheTreatysDatesNeedABordereau() {
        Quarter q3 = Quarter.parse("2026-Q3");
        assertThat(StatementCalculator.monthsRequired(q3, LocalDate.of(2026, 9, 1), null))
            .containsExactly(YearMonth.of(2026, 9));
        assertThat(StatementCalculator.monthsRequired(q3, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 15)))
            .containsExactly(YearMonth.of(2026, 7), YearMonth.of(2026, 8));
        assertThat(StatementCalculator.monthsRequired(q3, LocalDate.of(2027, 1, 1), null)).isEmpty();
    }
}
