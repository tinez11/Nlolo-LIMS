package tz.co.nlolo.lifeplatform.unitlinked.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * The forward-pricing rule (spec §5), in one place. An order received before a fund's cut-off on civil
 * day D is bound to D; at or after it, to D + 1. A price for D can only be approved at D's cut-off or
 * later. So a bound order can never meet a price that existed when it was received -- for a premium, a
 * surrender, a death or anything else. Civil time is always Dar es Salaam's: a UTC date would put 00:30
 * EAT on the previous day.
 */
public final class BindingRule {

    public static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private BindingRule() {}

    public static LocalDate boundDate(Instant receivedAt, LocalTime cutOff) {
        ZonedDateTime local = receivedAt.atZone(CIVIL_ZONE);
        return local.toLocalTime().isBefore(cutOff) ? local.toLocalDate() : local.toLocalDate().plusDays(1);
    }

    public static Instant earliestApproval(LocalDate valuationDate, LocalTime cutOff) {
        return valuationDate.atTime(cutOff).atZone(CIVIL_ZONE).toInstant();
    }

    public static LocalDate civilDate(Instant at) {
        return at.atZone(CIVIL_ZONE).toLocalDate();
    }
}
