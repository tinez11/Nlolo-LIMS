package tz.co.nlolo.lifeplatform.keycloak;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Tells the platform that a person's password just changed, so it can text them "your password was changed -- if this
 * wasn't you, call us" (2026-11-06, universal login step 2). The phone number is the platform's, not Keycloak's: the
 * client and agent records hold it for agents and customers, and the staff record for staff. So Keycloak names the
 * person -- tenant, Keycloak user id, party when there is one -- and the platform sends.
 *
 * <p>Signed like {@link ResetCodeTexter}'s request, with the same secret. Sent asynchronously: the person's own password
 * change must never wait on, or fail because of, the platform or its SMS gateway.
 */
final class PasswordChangeNotifier {

    private static final Logger log = Logger.getLogger(PasswordChangeNotifier.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final String url;
    private final String secret;

    PasswordChangeNotifier(String url, String secret) {
        this.url = url;
        this.secret = secret;
    }

    boolean configured() {
        return url != null && !url.isBlank() && secret != null && !secret.isBlank();
    }

    void passwordChanged(String tenantId, String userId, String partyId, String language) {
        if (!configured()) {
            log.warn("A password-changed notice was not sent: LP_PASSWORD_CHANGED_URL / LP_RESET_SMS_SECRET are not set");
            return;
        }
        String body = body(tenantId, userId, partyId, language);
        long now = System.currentTimeMillis() / 1000;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .header("X-Reset-Timestamp", Long.toString(now))
            .header("X-Reset-Signature", ResetCodes.sign(secret, now, body))
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            if (failure != null) {
                log.warnf("Could not reach the platform with a password-changed notice for user %s: %s", userId,
                    failure.toString());
            } else if (response.statusCode() >= 300) {
                log.warnf("The platform refused a password-changed notice for user %s: HTTP %d", userId,
                    response.statusCode());
            }
        });
    }

    /** The request body; a person with no party (staff) sends {@code "partyId":""}. */
    static String body(String tenantId, String userId, String partyId, String language) {
        return "{\"tenantId\":\"" + json(tenantId) + "\",\"userId\":\"" + json(userId) + "\",\"partyId\":\""
            + json(partyId) + "\",\"language\":\"" + json(language) + "\"}";
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
