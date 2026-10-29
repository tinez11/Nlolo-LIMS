package tz.co.nlolo.lifeplatform.keycloak;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * The arithmetic of a texted reset code (2026-10-28), apart from Keycloak so it can be tested on its own: making a
 * code, keeping only its hash, checking a guess in constant time, the hourly cap on sends, and signing the request that
 * asks the platform to text it.
 */
final class ResetCodes {

    /** Six digits: what a person can read off a phone and type, the size every bank's SMS code is. */
    static final int DIGITS = 6;
    /** The code lives as long as the emailed link does (user decision, 2026-10-28). */
    static final int LIFETIME_SECONDS = 15 * 60;
    /** Wrong guesses before the code is spent: five of a million, against a code that also expires. */
    static final int MAX_ATTEMPTS = 5;
    /** Texts an account may be sent in an hour, so the reset page cannot be used to bombard someone's phone. */
    static final int MAX_SENDS_PER_HOUR = 3;
    /** The gap before "Send a new code" works again. */
    static final int RESEND_GAP_SECONDS = 60;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ResetCodes() {}

    static String newCode() {
        return String.format("%0" + DIGITS + "d", RANDOM.nextInt((int) Math.pow(10, DIGITS)));
    }

    static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return HexFormat.of().formatHex(salt);
    }

    /** What is kept in place of the code: the code itself never sits in the session. */
    static String hash(String salt, String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest((salt + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** True when {@code guess} is the code; the comparison takes the same time whichever digit is wrong. */
    static boolean matches(String salt, String expectedHash, String guess) {
        if (salt == null || expectedHash == null || guess == null) return false;
        String typed = guess.replaceAll("[\\s-]", "");
        if (!typed.matches("\\d{" + DIGITS + "}")) return false;
        return MessageDigest.isEqual(hash(salt, typed).getBytes(StandardCharsets.US_ASCII),
            expectedHash.getBytes(StandardCharsets.US_ASCII));
    }

    /** The send times still inside the last hour, oldest first, from their stored comma-separated form. */
    static List<Long> recentSends(String stored, long nowSeconds) {
        List<Long> recent = new ArrayList<>();
        if (stored == null || stored.isBlank()) return recent;
        for (String part : stored.split(",")) {
            try {
                long at = Long.parseLong(part.trim());
                if (nowSeconds - at < 3600) recent.add(at);
            } catch (NumberFormatException ignored) {
                // a corrupt entry counts for nothing
            }
        }
        return recent;
    }

    static boolean maySend(List<Long> recentSends) {
        return recentSends.size() < MAX_SENDS_PER_HOUR;
    }

    static String store(List<Long> sends) {
        StringBuilder out = new StringBuilder();
        for (Long at : sends) {
            if (!out.isEmpty()) out.append(',');
            out.append(at);
        }
        return out.toString();
    }

    /** The signature the platform checks: HMAC-SHA256 over "timestamp.body", as the mobile-money callback does. */
    static String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign the reset-code request", e);
        }
    }
}
