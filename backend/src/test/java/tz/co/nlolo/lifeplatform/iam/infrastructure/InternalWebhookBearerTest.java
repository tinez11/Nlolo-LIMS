package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Authentik's calls to the backend carry a bearer secret of their own (2026-11-19, Authentik cut-over ticket 05). The
 * resource server must not read it as a sign-in token: it refused every sign-in code request with 401 before the
 * controller saw it. Everywhere else a bearer token is still a sign-in token.
 */
class InternalWebhookBearerTest {

    private final BearerTokenResolver resolver = SecurityConfig.bearerTokenResolver();

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setServletPath(path);
        request.addHeader("Authorization", "Bearer some-secret-or-token");
        return request;
    }

    @Test
    void anInternalWebhooksSecretIsNotASignInToken() {
        assertThat(resolver.resolve(post("/internal/staff-sign-in-code/sms"))).isNull();
        assertThat(resolver.resolve(post("/internal/password-changed"))).isNull();
    }

    @Test
    void everywhereElseABearerTokenIsStillRead() {
        assertThat(resolver.resolve(post("/me/access"))).isEqualTo("some-secret-or-token");
        assertThat(resolver.resolve(post("/internalish/thing"))).isEqualTo("some-secret-or-token");
    }
}
