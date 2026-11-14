package tz.co.nlolo.lifeplatform.keycloak;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks the platform to text a staff member their sign-in code (2026-11-14). The platform holds the phone, the language,
 * the wording and the on/off switch, so Keycloak names the person -- tenant and user id -- and reads back what
 * happened: SENT, OFF, NO_PHONE or NOT_SENT. Signed as the reset-code request is, with the same shared secret.
 *
 * <p>Never logs the code. An unreachable or unconfigured platform answers NOT_SENT: the person is never let in on a
 * failure.
 */
final class StaffCodeTexter {

    private static final Logger log = Logger.getLogger(StaffCodeTexter.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final String url;
    private final String secret;

    StaffCodeTexter(String url, String secret) {
        this.url = url;
        this.secret = secret;
    }

    /**
     * {@code loginPhone}: the {@code phone} attribute on the login, set when it was created on the server
     * (add-user.sh --phone) -- the platform uses it only while the staff record has no phone of its own.
     */
    String send(String tenantId, String userId, String code, String loginPhone) {
        if (url == null || url.isBlank() || secret == null || secret.isBlank()) {
            log.warn("A staff sign-in code was not texted: LP_STAFF_CODE_SMS_URL / LP_RESET_SMS_SECRET are not set");
            return "NOT_SENT";
        }
        String body = "{\"tenantId\":\"" + json(tenantId) + "\",\"userId\":\"" + json(userId) + "\",\"code\":\""
            + json(code) + "\",\"phone\":\"" + json(loginPhone) + "\"}";
        long now = System.currentTimeMillis() / 1000;
        try {
            HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Reset-Timestamp", Long.toString(now))
                    .header("X-Reset-Signature", ResetCodes.sign(secret, now, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warnf("The platform refused a staff sign-in code for user %s: HTTP %d", userId, response.statusCode());
                return "NOT_SENT";
            }
            return SignInCodes.result(response.body());
        } catch (Exception e) {
            log.warnf("Could not reach the platform to text a staff sign-in code for user %s: %s", userId, e.toString());
            return "NOT_SENT";
        }
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
