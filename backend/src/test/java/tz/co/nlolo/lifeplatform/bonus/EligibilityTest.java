package tz.co.nlolo.lifeplatform.bonus;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.bonus.domain.Eligibility;
import tz.co.nlolo.lifeplatform.bonus.domain.Eligibility.StatusRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Q3 and Q7, on a status record: never "in force", always the status on the valuation date. */
class EligibilityTest {

    private static final LocalDate VALUATION = LocalDate.of(2026, 12, 31);

    /** A moment on a civil day in Dar es Salaam. */
    private static Instant at(LocalDate day, int hour) {
        return day.atTime(LocalTime.of(hour, 0)).atZone(Eligibility.CIVIL_ZONE).toInstant();
    }

    private static StatusRow row(String status, LocalDate day) {
        return new StatusRow(status, null, at(day, 12));
    }

    private static final StatusRow ISSUED = new StatusRow("ACTIVE", new BigDecimal("1000000"), at(LocalDate.of(2026, 1, 10), 9));

    @Test
    void activeOrReinstatedOrSuspendedOnTheValuationDateIsEligible() {
        assertThat(Eligibility.refusal(List.of(ISSUED), VALUATION, false)).isEmpty();
        assertThat(Eligibility.refusal(List.of(ISSUED, row("SUSPENDED", LocalDate.of(2026, 6, 1))), VALUATION, false)).isEmpty();
    }

    @Test
    void notYetIssuedIsNotEligible() {
        assertThat(Eligibility.refusal(List.of(new StatusRow("ACTIVE", BigDecimal.TEN, at(LocalDate.of(2027, 1, 1), 9))),
            VALUATION, false)).contains("The policy was not yet issued on 2026-12-31");
    }

    @Test
    void lapsedOnTheValuationDateGetsNothingNewButAnEarlierLapseCuredIsFine() {
        assertThat(Eligibility.refusal(List.of(ISSUED, row("LAPSED", LocalDate.of(2026, 11, 1))), VALUATION, false))
            .contains("The policy was LAPSED on 2026-12-31");
        assertThat(Eligibility.refusal(List.of(ISSUED, row("LAPSED", LocalDate.of(2026, 3, 1)),
            row("REINSTATED", LocalDate.of(2026, 5, 1))), VALUATION, false)).isEmpty();
    }

    @Test
    void reinstatedIsEligibleFromTheFirstValuationDateOnOrAfterReinstatement() {
        List<StatusRow> rows = List.of(ISSUED, row("LAPSED", LocalDate.of(2026, 11, 1)), row("REINSTATED", VALUATION));
        // Reinstated ON the valuation date: the status at the END of that day is REINSTATED.
        assertThat(Eligibility.refusal(rows, VALUATION, false)).isEmpty();
        // The day before, it was still lapsed.
        assertThat(Eligibility.refusal(rows, VALUATION.minusDays(1), false)).contains("The policy was LAPSED on 2026-12-30");
    }

    @Test
    void paidUpFollowsTheVersionsContractRule() {
        List<StatusRow> rows = List.of(ISSUED, new StatusRow("PAID_UP", new BigDecimal("400000"), at(LocalDate.of(2026, 8, 1), 12)));
        assertThat(Eligibility.refusal(rows, VALUATION, false))
            .contains("The policy was paid-up on 2026-12-31, and this version's paid-up policies receive no new bonuses");
        assertThat(Eligibility.refusal(rows, VALUATION, true)).isEmpty();
        assertThat(Eligibility.sumAssuredOn(rows, VALUATION)).isEqualByComparingTo("400000");
        assertThat(Eligibility.sumAssuredOn(rows, LocalDate.of(2026, 7, 31))).isEqualByComparingTo("1000000");
    }

    @Test
    void proposedOrClosedIsNotEligible() {
        assertThat(Eligibility.refusal(List.of(new StatusRow("PROPOSED", BigDecimal.TEN, at(LocalDate.of(2026, 1, 10), 9))),
            VALUATION, false)).contains("The policy was PROPOSED on 2026-12-31");
        assertThat(Eligibility.refusal(List.of(ISSUED, row("SURRENDERED", LocalDate.of(2026, 10, 1))), VALUATION, false))
            .contains("The policy was SURRENDERED on 2026-12-31");
    }

    @Test
    void theCivilDayDecidesNotUtc() {
        // 01:00 on 1 January in Dar es Salaam is still 31 December in UTC. A lapse then is NOT on the
        // 31st in civil time, so the policy was still active at the end of the valuation date.
        StatusRow earlyLapse = new StatusRow("LAPSED", null, at(VALUATION.plusDays(1), 1));
        assertThat(Eligibility.refusal(List.of(ISSUED, earlyLapse), VALUATION, false)).isEmpty();
    }
}
