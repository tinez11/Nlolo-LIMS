package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.omnichannel.application.PasswordResetTexts;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Keycloak's "text this person a reset code" (2026-10-28): only a request signed with the shared secret, and recent,
 * reaches the sender. The signing mirrors the Keycloak extension's ResetCodes.sign: HMAC-SHA256 over "timestamp.body".
 */
class PasswordResetSmsControllerTest {

    private static final String SECRET = "test-reset-secret";
    private final PasswordResetTexts texts = mock(PasswordResetTexts.class);
    private final PasswordResetSmsController controller =
        new PasswordResetSmsController(texts, new ObjectMapper(), SECRET);
    private final UUID tenant = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();
    private final String body = "{\"realm\":\"agents\",\"tenantId\":\"" + tenant + "\",\"partyId\":\"" + party
        + "\",\"code\":\"042917\",\"language\":\"sw\"}";

    private static String sign(String secret, long at, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((at + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    @Test
    void aSignedRecentRequestTextsTheCode() throws Exception {
        long at = now();
        assertThat(controller.text(body, Long.toString(at), sign(SECRET, at, body)).getStatusCode().value()).isEqualTo(202);
        verify(texts).send(party, "042917", "sw");
    }

    @Test
    void anUnsignedWronglySignedOrStaleRequestIsRefused() throws Exception {
        long at = now();
        assertThat(controller.text(body, null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.text(body, Long.toString(at), sign("another-secret", at, body)).getStatusCode().value())
            .isEqualTo(401);
        String tampered = body.replace("042917", "111111");
        assertThat(controller.text(tampered, Long.toString(at), sign(SECRET, at, body)).getStatusCode().value())
            .isEqualTo(401);
        long old = at - 600;
        assertThat(controller.text(body, Long.toString(old), sign(SECRET, old, body)).getStatusCode().value())
            .isEqualTo(401);
        verify(texts, never()).send(any(), any(), any());
    }
}
