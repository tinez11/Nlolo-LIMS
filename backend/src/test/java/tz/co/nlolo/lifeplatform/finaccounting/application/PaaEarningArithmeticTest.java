package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** PAA earning's arithmetic (guide I-03): straight-line by days of cover, cumulative, all of it once the cover ends. */
class PaaEarningArithmeticTest {

    private static Map<String, Object> row(String amount, String reduced, String from, String to) {
        return Map.of("amount", new BigDecimal(amount), "reduced", new BigDecimal(reduced),
            "covers_from", Date.valueOf(from), "covers_to", Date.valueOf(to));
    }

    @Test
    void aYearsCoverEarnsByDaysElapsed() {
        Map<String, Object> year = row("36500.00", "0", "2026-01-01", "2026-12-31");
        assertThat(PaaEarningJob.earnedBy(year, LocalDate.of(2026, 1, 31))).isEqualByComparingTo("3100.00");
        assertThat(PaaEarningJob.earnedBy(year, LocalDate.of(2026, 2, 28))).isEqualByComparingTo("5900.00");
        assertThat(PaaEarningJob.earnedBy(year, LocalDate.of(2026, 12, 31))).isEqualByComparingTo("36500.00");
        assertThat(PaaEarningJob.earnedBy(year, LocalDate.of(2025, 12, 31))).isZero();
    }

    @Test
    void whatWasWaivedOrCreditedIsNeverEarned() {
        Map<String, Object> half = row("1200.00", "600.00", "2026-01-01", "2026-12-31");
        assertThat(PaaEarningJob.earnedBy(half, LocalDate.of(2027, 3, 31))).isEqualByComparingTo("600.00");
    }

    @Test
    void theLastCompletedMonthIsTheOneBeforeToday() {
        assertThat(PaaEarningJob.lastCompletedMonthEnd(LocalDate.of(2026, 10, 6))).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(PaaEarningJob.lastCompletedMonthEnd(LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 2, 28));
    }
}
