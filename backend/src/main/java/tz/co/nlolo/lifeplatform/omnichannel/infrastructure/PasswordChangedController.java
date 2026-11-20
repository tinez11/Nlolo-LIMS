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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Authentik telling the platform that a person changed or reset their own password (2026-11-06; Authentik cut-over
 * ticket 06, 2026-11-19), so they are texted about it. A notification rule posts {@code {eventId, userId, tenantId,
 * partyId, at}} with this endpoint's own bearer secret; Caddy keeps the path off the public address.
 *
 * <p>Answered 202 at once; the text goes in the background. Anything ignored on purpose -- a repeat of an event, a
 * login with no tenant -- is still answered 2xx, because Authentik retries anything else.
 */
@RestController
public class PasswordChangedController {

    public static final String PATH = "/internal/password-changed";

    private final PasswordChangedNotices notices;
    private final ObjectMapper json;
    private final byte[] expected;

    public PasswordChangedController(PasswordChangedNotices notices, ObjectMapper json,
                                     @Value("${app.password-changed.webhook-secret}") String secret) {
        this.notices = notices;
        this.json = json;
        this.expected = ("Bearer " + secret).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> passwordChanged(@RequestBody String body,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization == null
                || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        String eventId;
        UUID userId;
        UUID tenantId;
        UUID partyId;
        OffsetDateTime at;
        try {
            JsonNode request = json.readTree(body);
            eventId = request.path("eventId").asText();
            userId = UUID.fromString(request.path("userId").asText());
            tenantId = request.path("tenantId").isTextual() ? UUID.fromString(request.path("tenantId").asText()) : null;
            partyId = request.path("partyId").isTextual() ? UUID.fromString(request.path("partyId").asText()) : null;
            at = OffsetDateTime.parse(request.path("at").asText());
            if (eventId.isBlank()) throw new IllegalArgumentException("no event id");
        } catch (Exception malformed) {
            return ResponseEntity.badRequest().build();
        }
        if (tenantId == null) {
            // Authentik's own administrator, or any login outside a tenant: nobody the platform texts.
            return ResponseEntity.accepted().build();
        }
        // The person's own tenant, from their login: every lookup is held to it, as a signed-in request is.
        TenantContext.set(tenantId);
        try {
            notices.passwordChanged(eventId, userId, partyId, at.toInstant());
        } finally {
            TenantContext.clear();
        }
        return ResponseEntity.accepted().build();
    }
}
