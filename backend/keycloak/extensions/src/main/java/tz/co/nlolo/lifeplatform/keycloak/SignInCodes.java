package tz.co.nlolo.lifeplatform.keycloak;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The staff sign-in code's limits (2026-11-14, spec 2026-11-14-staff-sign-in-code-design.md), apart from Keycloak so
 * they can be tested on their own. Making, hashing and checking a code, and signing the request, are ResetCodes'.
 */
final class SignInCodes {

    /** A sign-in is happening now: five minutes, not the reset's fifteen. */
    static final int LIFETIME_SECONDS = 5 * 60;
    /** Three wrong codes end the attempt; the person signs in again for a new one. */
    static final int MAX_ATTEMPTS = 3;
    /**
     * Five texts an hour per account. Not the reset's three: staff sign in many times a day, and a 30-minute idle
     * timeout twice in an hour plus one mistyped code would otherwise lock someone out of their work.
     */
    static final int MAX_SENDS_PER_HOUR = 5;
    static final int RESEND_GAP_SECONDS = 60;

    private static final Pattern RESULT =
        Pattern.compile("\"result\"\\s*:\\s*\"(SENT|OFF|NO_PHONE|NOT_SENT)\"");

    private SignInCodes() {}

    /** Under the hourly cap, and a minute since the last text -- counted per account, across browsers. */
    static boolean maySend(List<Long> recentSends, long nowSeconds) {
        if (recentSends.size() >= MAX_SENDS_PER_HOUR) return false;
        long newest = recentSends.stream().mapToLong(Long::longValue).max().orElse(Long.MIN_VALUE / 2);
        return nowSeconds - newest >= RESEND_GAP_SECONDS;
    }

    /** SENT, OFF, NO_PHONE or NOT_SENT from the platform's {"result": ...}. Anything unreadable is NOT_SENT: never let in. */
    static String result(String body) {
        if (body == null) return "NOT_SENT";
        Matcher m = RESULT.matcher(body);
        return m.find() ? m.group(1) : "NOT_SENT";
    }
}
