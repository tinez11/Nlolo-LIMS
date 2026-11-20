package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.application.StaffSignInCodes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Authentik asking for a staff member's sign-in code (2026-11-14; Authentik cut-over ticket 05, 2026-11-19), with this
 * endpoint's own bearer secret; Caddy keeps the paths off the public address.
 *
 * <ul>
 *   <li>{@link #PATH}: text this code. Authentik's SMS stage posts {@code {tenantId, userId, code, phone}} through a
 *   webhook mapping.</li>
 *   <li>{@link #CHECK_PATH}: could a code be sent now? Asked by a policy before the code step, because Authentik's code
 *   pages do not show a refused send (the user's choice, 2026-11-19); a refusal ends the sign-in with its words.
 *   Asking never counts as a send.</li>
 * </ul>
 * Refusals are plain text, in the staff console's English: Authentik shows them as they are.
 */
@RestController
public class StaffSignInCodeController {

    public static final String PATH = "/internal/staff-sign-in-code/sms";
    public static final String CHECK_PATH = "/internal/staff-sign-in-code/check";

    static final String NO_PHONE = "No phone number is on file for you. Ask an administrator.";
    static final String TOO_MANY = "Too many codes. Wait a minute and try again.";
    static final String NOT_SENT = "We couldn't send your code. Try again.";

    private record Ask(UUID tenantId, UUID userId, String code, String loginPhone) {}

    private final StaffSignInCodes codes;
    private final ObjectMapper json;
    private final byte[] expected;

    public StaffSignInCodeController(StaffSignInCodes codes, ObjectMapper json,
                                     @Value("${app.staff-sign-in-code.webhook-secret}") String secret) {
        this.codes = codes;
        this.json = json;
        this.expected = ("Bearer " + secret).getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping(PATH)
    public ResponseEntity<String> text(@RequestBody String body,
                                       @RequestHeader(value = "Authorization", required = false) String authorization) {
        return answer(body, authorization, true, ask -> switch (codes.send(ask.userId(), ask.code(), ask.loginPhone())) {
            case SENT -> ResponseEntity.noContent().build();
            case NO_PHONE -> refusal(HttpStatus.UNPROCESSABLE_ENTITY, NO_PHONE);
            case TOO_MANY -> refusal(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
            case NOT_SENT -> refusal(HttpStatus.SERVICE_UNAVAILABLE, NOT_SENT);
        });
    }

    @PostMapping(CHECK_PATH)
    public ResponseEntity<String> check(@RequestBody String body,
                                        @RequestHeader(value = "Authorization", required = false) String authorization) {
        return answer(body, authorization, false, ask -> {
            Optional<StaffSignInCodes.Result> refused = codes.refusal(ask.userId(), ask.loginPhone());
            if (refused.isEmpty()) return ResponseEntity.noContent().build();
            return refused.get() == StaffSignInCodes.Result.NO_PHONE
                ? refusal(HttpStatus.UNPROCESSABLE_ENTITY, NO_PHONE)
                : refusal(HttpStatus.TOO_MANY_REQUESTS, TOO_MANY);
        });
    }

    /** The secret, then the body, then the person's own tenant -- every lookup is held to it -- around {@code handle}. */
    private ResponseEntity<String> answer(String body, String authorization, boolean needsCode,
                                          Function<Ask, ResponseEntity<String>> handle) {
        if (authorization == null
                || !MessageDigest.isEqual(expected, authorization.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Ask ask;
        try {
            JsonNode request = json.readTree(body);
            JsonNode phone = request.path("phone");
            ask = new Ask(UUID.fromString(request.path("tenantId").asText()),
                UUID.fromString(request.path("userId").asText()), request.path("code").asText(),
                phone.isTextual() ? phone.asText() : null);
        } catch (Exception malformed) {
            return ResponseEntity.badRequest().build();
        }
        if (needsCode && !ask.code().matches("\\d{6}")) {
            return ResponseEntity.badRequest().build();
        }
        TenantContext.set(ask.tenantId());
        try {
            return handle.apply(ask);
        } finally {
            TenantContext.clear();
        }
    }

    private static ResponseEntity<String> refusal(HttpStatus status, String words) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(words);
    }
}
