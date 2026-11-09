package tz.co.nlolo.lifeplatform.omnichannel.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The guard on the public quote link (2026-11-02, the user): anyone may open it with no login, so everything a login
 * would otherwise prove is proved here instead.
 *
 * <ul>
 *   <li><b>A person, not a script.</b> A Cloudflare Turnstile token, checked with Cloudflare on the server -- a bot
 *       posting straight to the API never reached the widget. The check is done once, when a visit starts.</li>
 *   <li><b>A short visit.</b> A passed check is exchanged for a signed visit token valid {@link #VISIT_MINUTES}
 *       minutes, so pricing as somebody types does not need a fresh challenge per keystroke.</li>
 *   <li><b>Not too much.</b> Fixed-window limits per address and per visit. In memory: the pilot runs one backend
 *       (deployment D1); a second instance would need them shared.</li>
 *   <li><b>One insurer.</b> The tenant is this deployment's, from configuration -- never from the request, which is
 *       the rule that keeps tenancy invisible everywhere else.</li>
 * </ul>
 */
@Service
public class PublicAccess {

    private static final Logger log = LoggerFactory.getLogger(PublicAccess.class);
    public static final int VISIT_MINUTES = 30;
    private static final String SITEVERIFY = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private final boolean enabled;
    private final UUID tenantId;
    private final boolean turnstileEnabled;
    private final String turnstileSecret;
    private final byte[] visitKey;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public PublicAccess(@Value("${app.public-quote.enabled:false}") boolean enabled,
                        @Value("${app.public-quote.tenant-id:}") String tenantId,
                        @Value("${app.public-quote.turnstile.enabled:true}") boolean turnstileEnabled,
                        @Value("${app.public-quote.turnstile.secret:}") String turnstileSecret,
                        @Value("${app.public-quote.visit-secret:}") String visitSecret,
                        ObjectMapper objectMapper) {
        this.enabled = enabled && !tenantId.isBlank();
        this.tenantId = tenantId.isBlank() ? null : UUID.fromString(tenantId.trim());
        this.turnstileEnabled = turnstileEnabled;
        this.turnstileSecret = turnstileSecret;
        // No configured secret: a fresh one each start, so visits simply end on a restart -- never a known key.
        byte[] key = new byte[32];
        if (visitSecret.isBlank()) {
            new SecureRandom().nextBytes(key);
        } else {
            key = visitSecret.getBytes(StandardCharsets.UTF_8);
        }
        this.visitKey = key;
        this.objectMapper = objectMapper;
        this.clock = Clock.systemUTC();
    }

    /** Refused: the link is switched off, or the request failed a check. {@code status} is the HTTP status to answer. */
    public static class Refused extends RuntimeException {
        private final int status;

        public Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** This deployment's insurer, or a 404 when the public link is switched off here. */
    public UUID tenant() {
        if (!enabled) {
            throw new Refused(404, "Online quotes are not available here");
        }
        return tenantId;
    }

    /** At most {@code limit} uses of {@code key} in each {@code window}; a 429 past it. */
    public void limit(String key, int limit, Duration window) {
        long now = clock.millis();
        Window w = windows.compute(key, (k, old) ->
            old == null || now - old.start >= window.toMillis() ? new Window(now, 1) : new Window(old.start, old.count + 1));
        if (w.count > limit) {
            throw new Refused(429, "Too many requests. Please wait a few minutes and try again.");
        }
        // Keep the map from growing without bound: forget windows long finished.
        if (windows.size() > 50_000) {
            windows.entrySet().removeIf(e -> now - e.getValue().start > Duration.ofHours(1).toMillis());
        }
    }

    private record Window(long start, int count) {}

    /**
     * A visit begins once Cloudflare says the browser passed its challenge: the token is checked with Cloudflare here,
     * on the server, and is single-use there. Answers a signed visit token.
     */
    public String startVisit(String turnstileToken, String remoteIp) {
        tenant();
        limit("visit:" + remoteIp, 20, Duration.ofHours(1));
        if (turnstileEnabled && !humanByCloudflare(turnstileToken, remoteIp)) {
            throw new Refused(403, "We could not confirm you are a person. Reload the page and try again.");
        }
        long expires = clock.instant().plus(Duration.ofMinutes(VISIT_MINUTES)).getEpochSecond();
        String payload = expires + "." + UUID.randomUUID();
        return payload + "." + sign(payload);
    }

    /** The visit token's own id, once its signature and expiry check out; a 401 otherwise. */
    public String visit(String visitToken) {
        tenant();
        if (visitToken == null) {
            throw new Refused(401, "Your session has ended. Reload the page to continue.");
        }
        int last = visitToken.lastIndexOf('.');
        int first = visitToken.indexOf('.');
        if (first <= 0 || last <= first) {
            throw new Refused(401, "Your session has ended. Reload the page to continue.");
        }
        String payload = visitToken.substring(0, last);
        byte[] given = visitToken.substring(last + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(given, sign(payload).getBytes(StandardCharsets.US_ASCII))) {
            throw new Refused(401, "Your session has ended. Reload the page to continue.");
        }
        long expires;
        try {
            expires = Long.parseLong(payload.substring(0, first));
        } catch (NumberFormatException e) {
            throw new Refused(401, "Your session has ended. Reload the page to continue.");
        }
        if (clock.instant().getEpochSecond() > expires) {
            throw new Refused(401, "Your session has ended. Reload the page to continue.");
        }
        return payload.substring(first + 1);
    }

    /**
     * Proof that a texted code confirmed {@code phone} in this visit (2026-11-08): signed like a visit token and bound
     * to it, so it cannot be carried into another visit, and valid only while the visit is.
     */
    public String confirmPhone(String phone, String visit) {
        long expires = clock.instant().plus(Duration.ofMinutes(VISIT_MINUTES)).getEpochSecond();
        String payload = "phone." + expires + "." + visit + "." + phone;
        return payload + "." + sign(payload);
    }

    /** The phone a {@link #confirmPhone} token proves for this visit; a 401 when it does not. */
    public String confirmedPhone(String token, String visit) {
        String expired = "Confirm your phone again: the code has to be entered in this visit.";
        if (token == null) {
            throw new Refused(401, expired);
        }
        int last = token.lastIndexOf('.');
        if (last <= 0) {
            throw new Refused(401, expired);
        }
        String payload = token.substring(0, last);
        byte[] given = token.substring(last + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(given, sign(payload).getBytes(StandardCharsets.US_ASCII))) {
            throw new Refused(401, expired);
        }
        String[] parts = payload.split("\\.");
        if (parts.length != 4 || !"phone".equals(parts[0]) || !parts[2].equals(visit)) {
            throw new Refused(401, expired);
        }
        try {
            if (clock.instant().getEpochSecond() > Long.parseLong(parts[1])) {
                throw new Refused(401, expired);
            }
        } catch (NumberFormatException e) {
            throw new Refused(401, expired);
        }
        return parts[3];
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(visitKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is unavailable", e);
        }
    }

    private boolean humanByCloudflare(String token, String remoteIp) {
        if (token == null || token.isBlank() || token.length() > 2048) {
            return false;
        }
        String form = "secret=" + enc(turnstileSecret) + "&response=" + enc(token)
            + (remoteIp == null ? "" : "&remoteip=" + enc(remoteIp));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(SITEVERIFY))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(),
                HttpResponse.BodyHandlers.ofString());
            JsonNode body = objectMapper.readTree(response.body());
            return body.path("success").asBoolean(false);
        } catch (java.io.IOException e) {
            // Fails closed: a challenge nobody could check is not a passed one.
            log.warn("Turnstile verification could not reach Cloudflare: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
