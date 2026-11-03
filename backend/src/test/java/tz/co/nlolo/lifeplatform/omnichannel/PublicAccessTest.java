package tz.co.nlolo.lifeplatform.omnichannel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.omnichannel.application.PublicAccess;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The public link's guard on its own: switched off, unchallenged, forged, and too busy (2026-11-02). */
class PublicAccessTest {

    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static PublicAccess access(boolean enabled, boolean turnstile) {
        return new PublicAccess(enabled, TENANT, turnstile, "", "a-visit-secret-of-at-least-thirty-two-chars",
            new ObjectMapper());
    }

    private static int status(Runnable call) {
        try {
            call.run();
            return 200;
        } catch (PublicAccess.Refused e) {
            return e.status();
        }
    }

    @Test
    void aSwitchedOffLinkAnswersNotFound() {
        assertThat(status(() -> access(false, false).tenant())).isEqualTo(404);
    }

    @Test
    void noChallengeTokenIsNoVisit() {
        // With Turnstile on, a missing token is refused before Cloudflare is ever asked.
        assertThat(status(() -> access(true, true).startVisit(null, "10.0.0.1"))).isEqualTo(403);
    }

    @Test
    void aVisitTokenIsSignedAndOnlyThisServersKeyOpensIt() {
        PublicAccess mine = access(true, false);
        String token = mine.startVisit(null, "10.0.0.2");
        assertThat(mine.visit(token)).isNotBlank();

        PublicAccess another = new PublicAccess(true, TENANT, false, "", "a-different-secret-of-thirty-two-chars!",
            new ObjectMapper());
        assertThat(status(() -> another.visit(token))).isEqualTo(401);
        assertThat(status(() -> mine.visit("garbage"))).isEqualTo(401);
        assertThat(status(() -> mine.visit(null))).isEqualTo(401);
    }

    @Test
    void pastTheLimitIsTooManyRequests() {
        PublicAccess guard = access(true, false);
        for (int i = 0; i < 3; i++) {
            guard.limit("k", 3, Duration.ofMinutes(1));
        }
        assertThat(status(() -> guard.limit("k", 3, Duration.ofMinutes(1)))).isEqualTo(429);
        // Another key is counted on its own.
        assertThat(status(() -> guard.limit("other", 3, Duration.ofMinutes(1)))).isEqualTo(200);
    }
}
