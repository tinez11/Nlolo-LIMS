package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;

/**
 * The signature on Keycloak's calls to the platform (the reset code, 2026-10-28; "password changed", 2026-11-06):
 * HMAC-SHA256 over "timestamp.body" with a secret both sides hold, refused when missing, wrong, or more than five minutes
 * from now. The same scheme the mobile-money callback uses.
 */
final class KeycloakSignature {

    static final String TIMESTAMP_HEADER = "X-Reset-Timestamp";
    static final String SIGNATURE_HEADER = "X-Reset-Signature";
    private static final long WINDOW_SECONDS = 300;

    private final byte[] secret;
    private final Clock clock;

    KeycloakSignature(String secret, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    boolean signedAndFresh(String body, String timestamp, String signature) {
        if (body == null || timestamp == null || signature == null) return false;
        long at;
        try {
            at = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - at) > WINDOW_SECONDS) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] expected = mac.doFinal((at + "." + body).getBytes(StandardCharsets.UTF_8));
            byte[] given = HexFormat.of().parseHex(signature.trim());
            return MessageDigest.isEqual(expected, given);
        } catch (Exception e) {
            return false;
        }
    }
}
