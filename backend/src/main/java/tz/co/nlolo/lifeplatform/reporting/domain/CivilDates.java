package tz.co.nlolo.lifeplatform.reporting.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Every date this module stores is a Tanzanian calendar day. An event at 22:30 UTC on the 10th happened on
 * the 11th in Dar es Salaam, and a month-end report must count it there. */
public final class CivilDates {
    private CivilDates() {}

    public static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    public static LocalDate of(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    public static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(ZONE));
    }
}
