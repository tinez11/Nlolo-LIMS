package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.application.PasswordResetTexts;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Keycloak's request to text a password-reset code (2026-10-28). Not a Keycloak bearer token -- Keycloak is the one
 * asking -- but the same signature the mobile-money callback carries: HMAC-SHA256 over "timestamp.body" with a secret
 * both sides hold, refused unsigned, wrongly signed, or more than five minutes old. Production also keeps the path off
 * the public address entirely (deploy/web/Caddyfile): Keycloak reaches the platform on the private network.
 *
 * <p>Answers 202 to every properly signed request, whether or not a text went out: Keycloak needs to learn nothing
 * about the person, and nothing here returns anything about them.
 */
@RestController
public class PasswordResetSmsController {

    public static final String PATH = "/internal/password-reset/sms";
    private static final long WINDOW_SECONDS = 300;

    private final PasswordResetTexts texts;
    private final ObjectMapper json;
    private final byte[] secret;
    private final Clock clock;

    public PasswordResetSmsController(PasswordResetTexts texts, ObjectMapper json,
                                      @Value("${password-reset.sms-secret}") String secret) {
        this.texts = texts;
        this.json = json;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = Clock.systemUTC();
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> text(@RequestBody String body,
                                     @RequestHeader(value = "X-Reset-Timestamp", required = false) String timestamp,
                                     @RequestHeader(value = "X-Reset-Signature", required = false) String signature) {
        if (!signedAndFresh(body, timestamp, signature)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UUID tenantId;
        UUID partyId;
        String code;
        String language;
        try {
            JsonNode request = json.readTree(body);
            tenantId = UUID.fromString(request.path("tenantId").asText());
            partyId = UUID.fromString(request.path("partyId").asText());
            code = request.path("code").asText();
            language = request.path("language").asText("en");
        } catch (Exception malformed) {
            return ResponseEntity.badRequest().build();
        }
        // The person's own tenant, from their login: every lookup below is held to it, as a signed-in request is.
        TenantContext.set(tenantId);
        try {
            texts.send(partyId, code, language);
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.accepted().build();
    }

    private boolean signedAndFresh(String body, String timestamp, String signature) {
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
