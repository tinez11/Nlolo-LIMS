package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.application.StaffSignInCodes;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Keycloak's "text this staff member a sign-in code" (2026-11-14): signed and recent, or nothing happens. */
class StaffSignInCodeControllerTest {

    private static final String SECRET = "test-reset-secret";
    private final StaffSignInCodes codes = mock(StaffSignInCodes.class);
    private final StaffSignInCodeController controller = new StaffSignInCodeController(codes, new ObjectMapper(), SECRET);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private static String sign(String secret, long at, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((at + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private String body(String code) {
        return "{\"tenantId\":\"" + tenant + "\",\"userId\":\"" + user + "\",\"code\":\"" + code + "\"}";
    }

    @Test
    void aSignedRequestIsAnsweredWithWhatHappenedInTheirOwnTenant() throws Exception {
        AtomicReference<UUID> seen = new AtomicReference<>();
        when(codes.send(user, "123456", "")).thenAnswer(i -> {
            seen.set(TenantContext.get());
            return StaffSignInCodes.Result.SENT;
        });
        long at = System.currentTimeMillis() / 1000;
        String b = body("123456");
        var response = controller.text(b, Long.toString(at), sign(SECRET, at, b));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(Map.of("result", "SENT"));
        assertThat(seen.get()).isEqualTo(tenant);
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    void thePhoneOnTheLoginIsPassedOn() throws Exception {
        when(codes.send(user, "123456", "+255700000002")).thenReturn(StaffSignInCodes.Result.SENT);
        long at = System.currentTimeMillis() / 1000;
        String b = "{\"tenantId\":\"" + tenant + "\",\"userId\":\"" + user + "\",\"code\":\"123456\","
            + "\"phone\":\"+255700000002\"}";
        assertThat(controller.text(b, Long.toString(at), sign(SECRET, at, b)).getBody()).isEqualTo(Map.of("result", "SENT"));
    }

    @Test
    void offIsAnsweredToo() throws Exception {
        when(codes.send(user, "123456", "")).thenReturn(StaffSignInCodes.Result.OFF);
        long at = System.currentTimeMillis() / 1000;
        String b = body("123456");
        assertThat(controller.text(b, Long.toString(at), sign(SECRET, at, b)).getBody()).isEqualTo(Map.of("result", "OFF"));
    }

    @Test
    void anUnsignedWronglySignedOrStaleRequestIsRefused() throws Exception {
        long at = System.currentTimeMillis() / 1000;
        String b = body("123456");
        assertThat(controller.text(b, null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.text(b, Long.toString(at), sign("other", at, b)).getStatusCode().value()).isEqualTo(401);
        long old = at - 600;
        assertThat(controller.text(b, Long.toString(old), sign(SECRET, old, b)).getStatusCode().value()).isEqualTo(401);
        verify(codes, never()).send(any(), any(), any());
    }

    @Test
    void aMalformedBodyOrACodeThatIsNotSixDigitsIsABadRequest() throws Exception {
        long at = System.currentTimeMillis() / 1000;
        String malformed = "{\"tenantId\":\"not-a-uuid\"}";
        assertThat(controller.text(malformed, Long.toString(at), sign(SECRET, at, malformed)).getStatusCode().value())
            .isEqualTo(400);
        String shortCode = body("12345");
        assertThat(controller.text(shortCode, Long.toString(at), sign(SECRET, at, shortCode)).getStatusCode().value())
            .isEqualTo(400);
        verify(codes, never()).send(any(), any(), any());
    }
}
