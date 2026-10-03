package tz.co.nlolo.lifeplatform.annuity.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Reading event payloads: a UUID in process, a String after any serialising hop -- accept both. */
final class Payloads {
    private Payloads() {}

    @SuppressWarnings("unchecked")
    static Map<String, Object> of(Object payload) {
        return (Map<String, Object>) payload;
    }

    static UUID uuid(Object value) {
        return value == null ? null : value instanceof UUID u ? u : UUID.fromString(String.valueOf(value));
    }

    static LocalDate date(Object value) {
        return value == null ? null : LocalDate.parse(String.valueOf(value));
    }

    /** An instant's civil date here -- never UTC's. */
    static LocalDate civilDate(Object instant) {
        return instant == null ? LocalDate.now(AnnuityApiImpl.CIVIL_ZONE)
            : Instant.parse(String.valueOf(instant)).atZone(AnnuityApiImpl.CIVIL_ZONE).toLocalDate();
    }
}
