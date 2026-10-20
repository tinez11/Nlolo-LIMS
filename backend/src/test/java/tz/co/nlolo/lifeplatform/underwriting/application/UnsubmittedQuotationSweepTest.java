package tz.co.nlolo.lifeplatform.underwriting.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the end-of-day sweep cuts (2026-10-20): quotations confirmed before today began, Africa/Dar_es_Salaam -- but
 * never one confirmed in the last hour, which an agent may still be onboarding past midnight.
 */
class UnsubmittedQuotationSweepTest {

    private static final ZoneId EAT = ZoneId.of("Africa/Dar_es_Salaam");

    private static Instant eat(int day, int hour, int minute) {
        return LocalDateTime.of(2026, 10, day, hour, minute).atZone(EAT).toInstant();
    }

    @Test
    void duringTheDayEverythingFromBeforeTodayGoes() {
        assertThat(UnsubmittedQuotationSweep.cutoff(eat(21, 10, 0))).isEqualTo(eat(21, 0, 0));
    }

    @Test
    void justAfterMidnightTheLastHourIsSpared() {
        // Confirmed at 23:50 on the 20th, still onboarding at 00:10 on the 21st: not swept.
        Instant cutoff = UnsubmittedQuotationSweep.cutoff(eat(21, 0, 10));
        assertThat(cutoff).isEqualTo(eat(20, 23, 10));
        assertThat(eat(20, 23, 50)).isAfter(cutoff);
        assertThat(eat(20, 22, 0)).isBefore(cutoff);
    }

    @Test
    void theCivilDayIsDarEsSalaamsNotUtcs() {
        // 02:30 EAT is 23:30 UTC the day before; "today" is still the 21st.
        assertThat(UnsubmittedQuotationSweep.cutoff(eat(21, 2, 30))).isEqualTo(eat(21, 0, 0));
    }
}
