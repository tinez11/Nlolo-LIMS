package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.AnnuitySchedule;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** An annuity stream's calendar and amounts (product step 5). */
class AnnuityScheduleTest {

    private static final BigDecimal BASE = new BigDecimal("294000.00");

    @Test
    void monthlyDueDatesStepFromTheFirstAndKeepItsDayWhereTheMonthHasOne() {
        LocalDate first = LocalDate.of(2026, 1, 31);
        assertThat(AnnuitySchedule.dueDate(first, "MONTHLY", 0)).isEqualTo(first);
        assertThat(AnnuitySchedule.dueDate(first, "MONTHLY", 1)).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(AnnuitySchedule.dueDate(first, "MONTHLY", 2)).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(AnnuitySchedule.dueDate(first, "QUARTERLY", 1)).isEqualTo(LocalDate.of(2026, 4, 30));
        assertThat(AnnuitySchedule.dueDate(first, "ANNUAL", 3)).isEqualTo(LocalDate.of(2029, 1, 31));
    }

    @Test
    void escalationCompoundsFromTheBaseOnEachAnniversaryAndRoundsOnce() {
        LocalDate first = LocalDate.of(2026, 11, 3);
        BigDecimal three = new BigDecimal("3");
        assertThat(AnnuitySchedule.amount(BASE, three, first, first, BigDecimal.ONE)).isEqualByComparingTo("294000.00");
        // Still year 0 the day before the first anniversary.
        assertThat(AnnuitySchedule.amount(BASE, three, first, first.plusYears(1).minusDays(1), BigDecimal.ONE))
            .isEqualByComparingTo("294000.00");
        assertThat(AnnuitySchedule.amount(BASE, three, first, first.plusYears(1), BigDecimal.ONE)).isEqualByComparingTo("302820.00");
        // 294,000 x 1.03^20 = 530,996.70 exactly to the cent (computed with integer arithmetic).
        assertThat(AnnuitySchedule.amount(BASE, three, first, first.plusYears(20), BigDecimal.ONE)).isEqualByComparingTo("530996.70");
    }

    @Test
    void noEscalationIsTheBaseForever() {
        LocalDate first = LocalDate.of(2026, 11, 3);
        assertThat(AnnuitySchedule.amount(BASE, BigDecimal.ZERO, first, first.plusYears(30), BigDecimal.ONE))
            .isEqualByComparingTo("294000.00");
    }

    @Test
    void theSurvivorMultiplierAppliesAfterEscalation() {
        LocalDate first = LocalDate.of(2026, 11, 3);
        // 50% of year 1's 302,820.00.
        assertThat(AnnuitySchedule.amount(BASE, new BigDecimal("3"), first, first.plusYears(1), new BigDecimal("0.5")))
            .isEqualByComparingTo("151410.00");
    }
}
