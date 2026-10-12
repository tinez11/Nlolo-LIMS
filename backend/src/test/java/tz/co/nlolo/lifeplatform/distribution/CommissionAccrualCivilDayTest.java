package tz.co.nlolo.lifeplatform.distribution;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.distribution.domain.CommissionAccrual;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** A commission line is dated on the Dar es Salaam day it was booked (UTC+3, no DST), never the UTC one. */
class CommissionAccrualCivilDayTest {

    @Test
    void anEarlyMorningLineInDarEsSalaamTakesTheDarDateNotTheUtcOne() {
        // 01:30 in Dar es Salaam is 22:30 UTC the evening before.
        assertThat(CommissionAccrual.civilDayOf(Instant.parse("2026-10-11T22:30:00Z")))
            .isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void theDarDayEndsAtTwentyOneHundredUtc() {
        assertThat(CommissionAccrual.civilDayOf(Instant.parse("2026-10-12T20:59:59Z")))
            .isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(CommissionAccrual.civilDayOf(Instant.parse("2026-10-12T21:00:00Z")))
            .isEqualTo(LocalDate.of(2026, 10, 13));
    }
}
