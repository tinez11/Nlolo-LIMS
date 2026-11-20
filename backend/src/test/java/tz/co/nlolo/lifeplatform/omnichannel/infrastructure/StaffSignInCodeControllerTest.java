package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.omnichannel.application.StaffSignInCodes;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Authentik's "text this staff member a sign-in code" (2026-11-14; Authentik cut-over ticket 05, 2026-11-19): a bearer
 * secret, and a reply Authentik shows as it is -- the status, and for a refusal the plain text the person reads.
 */
class StaffSignInCodeControllerTest {

    private static final String SECRET = "test-sign-in-code-secret";
    private final StaffSignInCodes codes = mock(StaffSignInCodes.class);
    private final StaffSignInCodeController controller = new StaffSignInCodeController(codes, new ObjectMapper(), SECRET);
    private final UUID tenant = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    private String body(String code) {
        return "{\"tenantId\":\"" + tenant + "\",\"userId\":\"" + user + "\",\"code\":\"" + code + "\","
            + "\"phone\":\"+255700000002\"}";
    }

    private ResponseEntity<String> call(StaffSignInCodes.Result result) {
        when(codes.send(user, "123456", "+255700000002")).thenReturn(result);
        return controller.text(body("123456"), "Bearer " + SECRET);
    }

    @Test
    void textedIsANoContentAnsweredInTheirOwnTenantWithTheLoginsPhonePassedOn() {
        AtomicReference<UUID> seen = new AtomicReference<>();
        when(codes.send(user, "123456", "+255700000002")).thenAnswer(i -> {
            seen.set(TenantContext.get());
            return StaffSignInCodes.Result.SENT;
        });

        ResponseEntity<String> response = controller.text(body("123456"), "Bearer " + SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(seen.get()).isEqualTo(tenant);
        assertThat(TenantContext.getOrNull()).isNull();
    }

    @Test
    void eachRefusalIsAStatusAndTheWordsThePersonReads() {
        ResponseEntity<String> noPhone = call(StaffSignInCodes.Result.NO_PHONE);
        assertThat(noPhone.getStatusCode().value()).isEqualTo(422);
        assertThat(noPhone.getBody()).isEqualTo("No phone number is on file for you. Ask an administrator.");
        assertThat(noPhone.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_PLAIN);

        ResponseEntity<String> tooMany = call(StaffSignInCodes.Result.TOO_MANY);
        assertThat(tooMany.getStatusCode().value()).isEqualTo(429);
        assertThat(tooMany.getBody()).isEqualTo("Too many codes. Wait a minute and try again.");

        ResponseEntity<String> notSent = call(StaffSignInCodes.Result.NOT_SENT);
        assertThat(notSent.getStatusCode().value()).isEqualTo(503);
        assertThat(notSent.getBody()).isEqualTo("We couldn't send your code. Try again.");
    }

    @Test
    void theCheckBeforeTheCodeStepAnswersWhetherACodeCanBeSentAndWhyNot() {
        String b = "{\"tenantId\":\"" + tenant + "\",\"userId\":\"" + user + "\",\"phone\":\"+255700000002\"}";
        AtomicReference<UUID> seen = new AtomicReference<>();
        when(codes.refusal(user, "+255700000002")).thenAnswer(i -> {
            // getOrNull: re-stubbing below calls this answer once more, outside any request.
            if (TenantContext.getOrNull() != null) seen.set(TenantContext.getOrNull());
            return java.util.Optional.empty();
        });
        assertThat(controller.check(b, "Bearer " + SECRET).getStatusCode().value()).isEqualTo(204);
        assertThat(seen.get()).isEqualTo(tenant);

        when(codes.refusal(user, "+255700000002")).thenReturn(java.util.Optional.of(StaffSignInCodes.Result.NO_PHONE));
        ResponseEntity<String> noPhone = controller.check(b, "Bearer " + SECRET);
        assertThat(noPhone.getStatusCode().value()).isEqualTo(422);
        assertThat(noPhone.getBody()).isEqualTo("No phone number is on file for you. Ask an administrator.");

        when(codes.refusal(user, "+255700000002")).thenReturn(java.util.Optional.of(StaffSignInCodes.Result.TOO_MANY));
        assertThat(controller.check(b, "Bearer " + SECRET).getBody()).isEqualTo("Too many codes. Wait a minute and try again.");

        assertThat(controller.check(b, "Bearer other").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.check("{}", "Bearer " + SECRET).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void noSecretOrAWrongOneIsRefused() {
        String b = body("123456");
        assertThat(controller.text(b, null).getStatusCode().value()).isEqualTo(401);
        assertThat(controller.text(b, "Bearer other").getStatusCode().value()).isEqualTo(401);
        assertThat(controller.text(b, SECRET).getStatusCode().value()).isEqualTo(401);
        verify(codes, never()).send(any(), any(), any());
    }

    @Test
    void aMalformedBodyOrACodeThatIsNotSixDigitsIsABadRequest() {
        assertThat(controller.text("{\"tenantId\":\"not-a-uuid\"}", "Bearer " + SECRET).getStatusCode().value())
            .isEqualTo(400);
        assertThat(controller.text(body("12345"), "Bearer " + SECRET).getStatusCode().value()).isEqualTo(400);
        verify(codes, never()).send(any(), any(), any());
    }
}
