package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.application.StaffSignInCodes;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Keycloak asking for a staff member's sign-in code to be texted (2026-11-14). Signed like the reset-code request
 * ({@link KeycloakSignature}) and kept off the public address by Caddy as that one is. Unlike it, this one answers:
 * Keycloak must know whether to ask for a code at all (OFF), to refuse (NO_PHONE), or to offer another (NOT_SENT).
 */
@RestController
public class StaffSignInCodeController {

    public static final String PATH = "/internal/staff-sign-in-code/sms";

    private final StaffSignInCodes codes;
    private final ObjectMapper json;
    private final KeycloakSignature signature;

    @Autowired
    public StaffSignInCodeController(StaffSignInCodes codes, ObjectMapper json,
                                     @Value("${password-reset.sms-secret}") String secret) {
        this(codes, json, new KeycloakSignature(secret, Clock.systemUTC()));
    }

    StaffSignInCodeController(StaffSignInCodes codes, ObjectMapper json, KeycloakSignature signature) {
        this.codes = codes;
        this.json = json;
        this.signature = signature;
    }

    @PostMapping(PATH)
    public ResponseEntity<Map<String, String>> text(@RequestBody String body,
            @RequestHeader(value = KeycloakSignature.TIMESTAMP_HEADER, required = false) String timestamp,
            @RequestHeader(value = KeycloakSignature.SIGNATURE_HEADER, required = false) String sig) {
        if (!signature.signedAndFresh(body, timestamp, sig)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        UUID tenantId;
        UUID userId;
        String code;
        try {
            JsonNode request = json.readTree(body);
            tenantId = UUID.fromString(request.path("tenantId").asText());
            userId = UUID.fromString(request.path("userId").asText());
            code = request.path("code").asText();
        } catch (Exception malformed) {
            return ResponseEntity.badRequest().build();
        }
        if (!code.matches("\\d{6}")) {
            return ResponseEntity.badRequest().build();
        }
        // The person's own tenant, from their login: every lookup is held to it, as a signed-in request is.
        TenantContext.set(tenantId);
        try {
            return ResponseEntity.ok(Map.of("result", codes.send(userId, code).name()));
        } finally {
            TenantContext.clear();
        }
    }
}
