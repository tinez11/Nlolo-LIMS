package tz.co.nlolo.lifeplatform.reporting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Reads event payload fields whether they arrive as Java objects (a live event) or as JSON strings and
 * numbers (the same event replayed from the audit journal). Every field goes through
 * {@code String.valueOf} and is parsed, so no handler ever casts {@code (UUID) payload.get(..)} -- the
 * cast regreporting uses, which a replayed payload would break.
 */
public final class PayloadReader {
    private PayloadReader() {}

    public static String text(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public static UUID uuid(Map<String, Object> payload, String key) {
        String s = text(payload, key);
        return s == null || s.isBlank() ? null : UUID.fromString(s);
    }

    public static LocalDate date(Map<String, Object> payload, String key) {
        String s = text(payload, key);
        return s == null || s.isBlank() ? null : LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
    }

    /** ISO-8601, or epoch seconds with a fraction -- the shape Jackson writes an Instant in when dates are
     * serialised as timestamps. */
    public static Instant instant(Map<String, Object> payload, String key) {
        String s = text(payload, key);
        if (s == null || s.isBlank()) return null;
        if (s.matches("\\d+(\\.\\d+)?")) {
            BigDecimal seconds = new BigDecimal(s);
            return Instant.ofEpochSecond(seconds.longValue(),
                seconds.remainder(BigDecimal.ONE).movePointRight(9).longValue());
        }
        return Instant.parse(s);
    }

    public static Integer integer(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return Integer.valueOf(String.valueOf(value));
    }

    public static boolean flag(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        return value instanceof Boolean b ? b : value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    /** A money object's amount ({@code {"amount": "...", "currencyCode": "..."}}), or a plain decimal. */
    public static BigDecimal amount(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> money) value = money.get("amount");
        return value == null ? null : new BigDecimal(String.valueOf(value));
    }

    public static String currency(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof Map<?, ?> money && money.get("currencyCode") != null) {
            return String.valueOf(money.get("currencyCode"));
        }
        return null;
    }
}
