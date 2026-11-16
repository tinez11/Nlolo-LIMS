package tz.co.nlolo.lifeplatform.regreporting.application;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A quarter is a civil quarter in Tanzania (2026-11-15). Read in UTC, an event in the first three hours of a quarter
 * -- 00:00 to 03:00 East Africa Time -- was counted in the quarter before.
 */
class QuarterAttributionTest {

    /** 1 October 2026, 01:30 in Dar es Salaam: still 30 September in UTC. */
    private static final String JUST_INTO_Q4 = "2026-09-30T22:30:00Z";

    @Test
    void anEventInTheFirstHoursOfAQuarterBelongsToThatQuarter() {
        assertThat(ProjectionSupport.quarterOfInstant(JUST_INTO_Q4)).isEqualTo("2026-Q4");
        assertThat(ProjectionSupport.quarterOfOccurrence(Instant.parse(JUST_INTO_Q4))).isEqualTo("2026-Q4");
    }

    @Test
    void anEventBeforeMidnightInDarEsSalaamStaysInTheQuarterItHappenedIn() {
        // 30 September 2026, 23:59 in Dar es Salaam.
        assertThat(ProjectionSupport.quarterOfInstant("2026-09-30T20:59:00Z")).isEqualTo("2026-Q3");
    }
}
