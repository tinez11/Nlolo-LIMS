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
import tz.co.nlolo.lifeplatform.omnichannel.application.PasswordChangedNotices;

import java.time.Clock;
import java.util.UUID;

/**
 * Keycloak telling the platform that a password changed (2026-11-06), so the person is texted about it. Signed like the
 * reset-code request ({@link KeycloakSignature}), and kept off the public address by Caddy as that one is. Answers 202
 * to every properly signed request, whether or not a text went out.
 */
@RestController
public class PasswordChangedController {

    public static final String PATH = "/internal/password-changed";

    private final PasswordChangedNotices notices;
    private final ObjectMapper json;
    private final KeycloakSignature signature;

    public PasswordChangedController(PasswordChangedNotices notices, ObjectMapper json,
                                     @Value("${password-reset.sms-secret}") String secret) {
        this(notices, json, new KeycloakSignature(secret, Clock.systemUTC()));
    }

    PasswordChangedController(PasswordChangedNotices notices, ObjectMapper json, KeycloakSignature signature) {
        this.notices = notices;
        this.json = json;
        this.signature = signature;
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> passwordChanged(@RequestBody String body,
            @RequestHeader(value = KeycloakSignature.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = KeycloakSignature.SIGNATURE_HEADER, required = false) String sig) {
        if (!signature.signedAndFresh(body, timestamp, sig)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UUID tenantId;
        UUID userId;
        UUID partyId;
        String language;
        try {
            JsonNode request = json.readTree(body);
            tenantId = UUID.fromString(request.path("tenantId").asText());
            userId = UUID.fromString(request.path("userId").asText());
            String party = request.path("partyId").asText("");
            partyId = party.isBlank() ? null : UUID.fromString(party);
            language = request.path("language").asText("en");
        } catch (Exception malformed) {
            return ResponseEntity.badRequest().build();
        }
        // The person's own tenant, from their login: every lookup is held to it, as a signed-in request is.
        TenantContext.set(tenantId);
        try {
            notices.passwordChanged(userId, partyId, language);
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.accepted().build();
    }
}
