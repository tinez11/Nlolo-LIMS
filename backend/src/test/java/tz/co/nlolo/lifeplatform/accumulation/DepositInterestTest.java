package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.accumulation.application.DepositInterest;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Spec §7: a rate for the term is that rate, exactly, and pro rata is by day, rounded once. */
class DepositInterestTest {

    private static final BigDecimal MILLION = new BigDecimal("1000000.00");
    private static final LocalDate START = LocalDate.of(2026, 1, 15);

    @Test
    void aWholeTermPaysTheRateExactly() {
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), START, START.plusMonths(3), START.plusMonths(3)))
            .isEqualByComparingTo("30000.00");
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("4"), START, START.plusMonths(6), START.plusMonths(6)))
            .isEqualByComparingTo("40000.00");
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("5"), START, START.plusMonths(12), START.plusMonths(12)))
            .isEqualByComparingTo("50000.00");
        assertThat(DepositInterest.full(MILLION, new BigDecimal("3"))).isEqualByComparingTo("30000.00");
    }

    @Test
    void anEarlyExitEarnsByTheDay() {
        // 15 Jan -> 15 Apr is 90 days; 45 held is half: 15,000.00.
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), START, START.plusMonths(3), START.plusDays(45)))
            .isEqualByComparingTo("15000.00");
        // 45 of 92 days (1 Jul -> 1 Oct): 30,000 x 45 / 92 = 14,673.913... -> 14,673.91, once.
        LocalDate july = LocalDate.of(2026, 7, 1);
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), july, july.plusMonths(3), july.plusDays(45)))
            .isEqualByComparingTo("14673.91");
    }

    @Test
    void neverBeforeTheStartAndNeverPastTheTerm() {
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), START, START.plusMonths(3), START.minusDays(5)))
            .isEqualByComparingTo("0.00");
        assertThat(DepositInterest.accrued(MILLION, new BigDecimal("3"), START, START.plusMonths(3), START.plusYears(1)))
            .isEqualByComparingTo("30000.00");
    }
}
