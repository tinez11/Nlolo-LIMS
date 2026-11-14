package tz.co.nlolo.lifeplatform.keycloak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The staff sign-in code's limits and the platform's answer (2026-11-14). */
class SignInCodesTest {

    @Test
    void fiveTextsAnHourAndOneAMinute() {
        long now = 10_000;
        assertTrue(SignInCodes.maySend(List.of(), now));
        assertFalse(SignInCodes.maySend(List.of(now - 30), now), "a minute apart");
        assertTrue(SignInCodes.maySend(List.of(now - 3000, now - 2400, now - 1800, now - 1200), now));
        assertFalse(SignInCodes.maySend(List.of(now - 3000, now - 2400, now - 1800, now - 1200, now - 600), now));
    }

    @Test
    void theAnswerIsReadFromThePlatformsReplyAndAnythingElseIsNotSent() {
        assertEquals("SENT", SignInCodes.result("{\"result\":\"SENT\"}"));
        assertEquals("OFF", SignInCodes.result("{\"result\":\"OFF\"}"));
        assertEquals("NO_PHONE", SignInCodes.result("{ \"result\" : \"NO_PHONE\" }"));
        assertEquals("NOT_SENT", SignInCodes.result("{\"result\":\"OPEN_SESAME\"}"));
        assertEquals("NOT_SENT", SignInCodes.result("garbage"));
        assertEquals("NOT_SENT", SignInCodes.result(null));
    }

    @Test
    void theCodeLivesFiveMinutesAndThreeWrongGuessesEndIt() {
        assertEquals(300, SignInCodes.LIFETIME_SECONDS);
        assertEquals(3, SignInCodes.MAX_ATTEMPTS);
    }
}
