package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.application.PasswordResets;
import tz.co.nlolo.lifeplatform.omnichannel.application.PublicAccess;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The /reset page's two calls (2026-11-19, Authentik cut-over ticket 07): behind a visit, which Turnstile started, in
 * the configured insurer's tenant -- never one from the request -- even with the quote link switched off.
 */
class PasswordResetControllerTest {

    static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private final PasswordResets resets = mock(PasswordResets.class);
    /** The quote link switched OFF, an insurer configured, Turnstile off for the test. */
    private final PublicAccess access = new PublicAccess(false, TENANT, false, "",
        "a-visit-secret-of-at-least-thirty-two-chars", new ObjectMapper());
    private final PasswordResetController controller = new PasswordResetController(resets, access);
    private final MockHttpServletRequest http = new MockHttpServletRequest();

    private String visit() {
        return access.startVisit(null, "10.0.0.1");
    }

    @Test
    void askingForACodeIsAnsweredTheSameWhateverHappensInTheInsurersTenant() {
        AtomicReference<UUID> seen = new AtomicReference<>();
        doAnswer(i -> {
            seen.set(TenantContext.getOrNull());
            return null;
        }).when(resets).request("0713000101", true);

        var answer = controller.request(new PasswordResetController.ResetRequest("0713000101", "sw"), visit(), http);

        assertThat(answer.getStatusCode().value()).isEqualTo(204);
        assertThat(seen.get()).isEqualTo(UUID.fromString(TENANT));
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    void theRightCodeAnswersTheLinkToFollow() {
        when(resets.check("0713000101", "123456", false)).thenReturn("https://login.example.tz/if/flow/x/?flow_token=t");

        var answer = controller.check(new PasswordResetController.ResetCheck("0713000101", "123456", "en"), visit(), http);

        assertThat(answer.getBody()).isEqualTo(new PasswordResetController.RecoveryLink("https://login.example.tz/if/flow/x/?flow_token=t"));
    }

    @Test
    void noVisitIsRefusedBeforeAnythingHappens() {
        assertThatThrownBy(() -> controller.request(new PasswordResetController.ResetRequest("0713000101", "en"), null, http))
            .isInstanceOf(PublicAccess.Refused.class)
            .satisfies(e -> assertThat(((PublicAccess.Refused) e).status()).isEqualTo(401));
        verify(resets, never()).request(anyString(), anyBoolean());
    }

    @Test
    void aRefusalIsTheirWordsWithTooManyAsA429() {
        ProblemDetail tooMany = controller.refused(refusal("Too many codes for this number. Try again in an hour.", true));
        assertThat(tooMany.getStatus()).isEqualTo(429);
        assertThat(tooMany.getDetail()).isEqualTo("Too many codes for this number. Try again in an hour.");

        ProblemDetail wrong = controller.refused(refusal("That code is not right. 4 tries left.", false));
        assertThat(wrong.getStatus()).isEqualTo(422);
    }

    private static PasswordResets.Refused refusal(String message, boolean tooMany) {
        PasswordResets.Refused refused = mock(PasswordResets.Refused.class);
        when(refused.getMessage()).thenReturn(message);
        when(refused.tooMany()).thenReturn(tooMany);
        return refused;
    }
}
