package tz.co.nlolo.lifeplatform.keycloak;

import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks the platform to text a reset code to a person's phone (2026-10-28). The phone number lives in the platform's
 * client and agent records, not in Keycloak, so Keycloak names the person -- their tenant and party -- and the
 * platform looks the number up and sends through its own SMS gateway, the one every other text goes through. The
 * request is signed with a secret both sides hold; the platform refuses anything unsigned or stale.
 *
 * <p>Never logs the code. A failure is logged without it, and the person sees the same page either way: whether a text
 * went out says something about the account, and the page says nothing about the account.
 */
final class ResetCodeTexter {

    private static final Logger log = Logger.getLogger(ResetCodeTexter.class);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private final String url;
    private final String secret;

    ResetCodeTexter(String url, String secret) {
        this.url = url;
        this.secret = secret;
    }

    boolean configured() {
        return url != null && !url.isBlank() && secret != null && !secret.isBlank();
    }

    void send(String realm, String tenantId, String partyId, String code, String language) {
        if (!configured()) {
            log.warn("A reset code was not texted: LP_RESET_SMS_URL / LP_RESET_SMS_SECRET are not set");
            return;
        }
        String body = "{\"realm\":\"" + json(realm) + "\",\"tenantId\":\"" + json(tenantId) + "\",\"partyId\":\""
            + json(partyId) + "\",\"code\":\"" + json(code) + "\",\"language\":\"" + json(language) + "\"}";
        long now = System.currentTimeMillis() / 1000;
        try {
            HttpResponse<Void> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .header("X-Reset-Timestamp", Long.toString(now))
                    .header("X-Reset-Signature", ResetCodes.sign(secret, now, body))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 300) {
                log.warnf("The platform refused to text a reset code for party %s in realm %s: HTTP %d", partyId, realm,
                    response.statusCode());
            }
        } catch (Exception e) {
            log.warnf("Could not reach the platform to text a reset code for party %s in realm %s: %s", partyId, realm,
                e.toString());
        }
    }

    private static String json(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
