package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.application.PasswordChangedNotices;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authentik's "this password changed" notice (2026-11-06; Authentik cut-over ticket 06, 2026-11-19): a bearer secret,
 * answered 202 at once, and 2xx to anything ignored on purpose so Authentik does not retry it.
 */
class PasswordChangedControllerTest {

    private static final String SECRET = "test-password-changed-secret";
    private final PasswordChangedNotices notices = mock(PasswordChangedNotices.class);
    private final PasswordChangedController controller =
        new PasswordChangedController(notices, new ObjectMapper(), SECRET);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();
    private final UUID party = UUID.randomUUID();

    private String body(String tenantId, String partyId) {
        return "{\"eventId\":\"ev-1\",\"userId\":\"" + user + "\",\"tenantId\":" + tenantId + ",\"partyId\":" + partyId
            + ",\"at\":\"2026-11-19T11:32:00.123456+00:00\"}";
    }

    @Test
    void aNoticeIsPassedOnInThePersonsOwnTenantAndAnsweredAtOnce() {
        AtomicReference<UUID> seen = new AtomicReference<>();
        when(notices.passwordChanged("ev-1", user, party, Instant.parse("2026-11-19T11:32:00.123456Z"))).thenAnswer(i -> {
            seen.set(TenantContext.getOrNull());
            return true;
        });

        var response = controller.passwordChanged(body("\"" + tenant + "\"", "\"" + party + "\""), "Bearer " + SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(seen.get()).isEqualTo(tenant);
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    void staffHaveNoPartyAndADuplicateIsStillAccepted() {
        when(notices.passwordChanged("ev-1", user, null, Instant.parse("2026-11-19T11:32:00.123456Z"))).thenReturn(false);
        assertThat(controller.passwordChanged(body("\"" + tenant + "\"", "null"), "Bearer " + SECRET)
            .getStatusCode().value()).isEqualTo(202);
    }

    /** A login with no tenant (Authentik's own administrator) is nobody the platform texts: accepted, not retried. */
    @Test
    void aLoginWithNoTenantIsAcceptedAndIgnored() {
        assertThat(controller.passwordChanged(body("null", "null"), "Bearer " + SECRET).getStatusCode().value())
            .isEqualTo(202);
        verify(notices, never()).passwordChanged(any(), any(), any(), any());
    }

    @Test
    void noSecretOrAWrongOneIsRefused() {
        String b = body("\"" + tenant + "\"", "null");
        assertThat(controller.passwordChanged(b, null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.passwordChanged(b, "Bearer other").getStatusCode().value()).isEqualTo(401);
        verify(notices, never()).passwordChanged(any(), any(), any(), any());
    }

    @Test
    void aMalformedBodyIsABadRequest() {
        assertThat(controller.passwordChanged("{\"userId\":\"x\"}", "Bearer " + SECRET).getStatusCode().value())
            .isEqualTo(400);
    }
}
