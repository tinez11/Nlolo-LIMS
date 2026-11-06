package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.omnichannel.application.PasswordChangedNotices;

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

/** Keycloak's "this password changed" (2026-11-06): signed and recent, or nothing happens. */
class PasswordChangedControllerTest {

    private static final String SECRET = "test-reset-secret";
    private final PasswordChangedNotices notices = mock(PasswordChangedNotices.class);
    private final PasswordChangedController controller =
        new PasswordChangedController(notices, new ObjectMapper(), SECRET);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();

    private static String sign(String secret, long at, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal((at + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private String body(String partyId) {
        return "{\"tenantId\":\"" + tenant + "\",\"userId\":\"" + user + "\",\"partyId\":\"" + partyId
            + "\",\"language\":\"sw\"}";
    }

    @Test
    void aSignedNoticeForAPartyOrForStaffIsPassedOn() throws Exception {
        long at = System.currentTimeMillis() / 1000;
        String forParty = body(party.toString());
        assertThat(controller.passwordChanged(forParty, Long.toString(at), sign(SECRET, at, forParty))
            .getStatusCode().value()).isEqualTo(202);
        verify(notices).passwordChanged(user, party, "sw");

        String forStaff = body("");
        assertThat(controller.passwordChanged(forStaff, Long.toString(at), sign(SECRET, at, forStaff))
            .getStatusCode().value()).isEqualTo(202);
        verify(notices).passwordChanged(user, null, "sw");
    }

    @Test
    void anUnsignedWronglySignedOrStaleNoticeIsRefused() throws Exception {
        long at = System.currentTimeMillis() / 1000;
        String b = body(party.toString());
        assertThat(controller.passwordChanged(b, null, null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.passwordChanged(b, Long.toString(at), sign("other", at, b)).getStatusCode().value())
            .isEqualTo(401);
        long old = at - 600;
        assertThat(controller.passwordChanged(b, Long.toString(old), sign(SECRET, old, b)).getStatusCode().value())
            .isEqualTo(401);
        verify(notices, never()).passwordChanged(any(), any(), any());
    }

    @Test
    void aMalformedBodyIsABadRequest() throws Exception {
        long at = System.currentTimeMillis() / 1000;
        String b = "{\"tenantId\":\"not-a-uuid\"}";
        assertThat(controller.passwordChanged(b, Long.toString(at), sign(SECRET, at, b)).getStatusCode().value())
            .isEqualTo(400);
    }
}
