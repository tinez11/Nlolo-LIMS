package tz.co.nlolo.lifeplatform.billing;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.billing.domain.BillingSchedule;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Due dates are counted from the schedule's anchor (billing V12, 2026-10-29). Each used to be the previous one plus a
 * month, so a policy first due on the 29th fell to the 28th after February for good -- and fitted thirteen instalments
 * into its first twelve months, which six tests caught only when the suite ran on the 29th. These dates are fixed, so
 * this runs the same on every day of the month.
 */
class BillingScheduleAnchorTest {

    private static BillingSchedule monthly(LocalDate firstDue) {
        return BillingSchedule.inAdvance(UUID.randomUUID(), "POL-1", "MONTHLY", new BigDecimal("1000"), "TZS", firstDue, null);
    }

    private static List<LocalDate> twelve(BillingSchedule schedule, LocalDate from) {
        List<LocalDate> dates = new ArrayList<>();
        LocalDate due = from;
        for (int i = 0; i < 12; i++) {
            dates.add(due);
            due = schedule.periodAfter(due);
        }
        return dates;
    }

    @Test
    void theTwentyNinthStaysTheTwentyNinthAfterFebruary() {
        BillingSchedule schedule = monthly(LocalDate.of(2026, 10, 29));
        List<LocalDate> dates = twelve(schedule, LocalDate.of(2026, 10, 29));
        assertThat(dates).contains(LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 29), LocalDate.of(2027, 9, 29));
        // Twelve months of instalments, the last a month before the anniversary -- not a thirteenth inside the year.
        assertThat(dates.get(11)).isEqualTo(LocalDate.of(2027, 9, 29));
    }

    @Test
    void theThirtyFirstReturnsWheneverTheMonthHasOne() {
        BillingSchedule schedule = monthly(LocalDate.of(2027, 1, 31));
        assertThat(twelve(schedule, LocalDate.of(2027, 1, 31)).subList(0, 4)).containsExactly(
            LocalDate.of(2027, 1, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 31), LocalDate.of(2027, 4, 30));
    }

    @Test
    void aScheduleThatHadAlreadyDriftedReturnsToItsDay() {
        // Raised before the fix: the 28th of March for a schedule first due on the 29th of October.
        BillingSchedule schedule = monthly(LocalDate.of(2026, 10, 29));
        assertThat(schedule.periodAfter(LocalDate.of(2027, 3, 28))).isEqualTo(LocalDate.of(2027, 4, 29));
    }

    @Test
    void quarterlyAndYearlyCountFromTheAnchorToo() {
        BillingSchedule quarterly = BillingSchedule.inAdvance(UUID.randomUUID(), "POL-2", "QUARTERLY", BigDecimal.TEN, "TZS",
            LocalDate.of(2026, 11, 30), null);
        assertThat(quarterly.periodAfter(LocalDate.of(2026, 11, 30))).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(quarterly.periodAfter(LocalDate.of(2027, 2, 28))).isEqualTo(LocalDate.of(2027, 5, 30));

        BillingSchedule leap = BillingSchedule.inAdvance(UUID.randomUUID(), "POL-3", "ANNUALLY", BigDecimal.TEN, "TZS",
            LocalDate.of(2028, 2, 29), null);
        assertThat(leap.periodAfter(LocalDate.of(2028, 2, 29))).isEqualTo(LocalDate.of(2029, 2, 28));
        assertThat(leap.periodAfter(LocalDate.of(2031, 2, 28))).isEqualTo(LocalDate.of(2032, 2, 29));
    }

    @Test
    void thePeriodBeforeIsCountedFromTheAnchor() {
        BillingSchedule schedule = monthly(LocalDate.of(2026, 10, 31));
        assertThat(schedule.periodBefore(LocalDate.of(2027, 3, 31))).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(schedule.periodBefore(LocalDate.of(2026, 10, 31))).isEqualTo(LocalDate.of(2026, 9, 30));
    }

    @Test
    void aRestatedScheduleKeepsItsAnchorAndItsConvention() {
        BillingSchedule schedule = monthly(LocalDate.of(2026, 10, 29));
        schedule.advanceNextDueDate(LocalDate.of(2027, 3, 29));
        BillingSchedule restated = schedule.restatedAt(new BigDecimal("1200"));
        assertThat(restated.getAnchorDate()).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThat(restated.isBilledInAdvance()).isTrue();
        assertThat(restated.getNextDueDate()).isEqualTo(LocalDate.of(2027, 3, 29));
        assertThat(restated.getPremiumAmount()).isEqualByComparingTo("1200");
    }
}
