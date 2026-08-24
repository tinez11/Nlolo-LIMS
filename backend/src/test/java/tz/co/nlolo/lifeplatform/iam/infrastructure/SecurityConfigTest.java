package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Same-package test (final-review Finding 6): calls SecurityConfig.authoritiesFor directly --
// it's package-private, and a same-package test class can access it with no production-side
// visibility-widening helper needed. Replaces the deleted SecurityConfigTestSupport, whose only
// purpose was exposing that method to this test from a different package.
class SecurityConfigTest {

    @Test
    void staffJwtWithRealmRolesGetsRoleAuthoritiesAndRealmMarker() {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .claim("realm_access", Map.of("roles", List.of("ADMIN", "UNDERWRITER")))
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

        var authorities = SecurityConfig.authoritiesFor(jwt, "STAFF");

        assertThat(authorities).extracting(a -> a.getAuthority())
            .containsExactlyInAnyOrder("ROLE_REALM_STAFF", "ROLE_ADMIN", "ROLE_UNDERWRITER");
    }

    @Test
    void jwtWithNoRealmAccessClaimStillGetsRealmMarker() {
        Jwt jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

        var authorities = SecurityConfig.authoritiesFor(jwt, "CUSTOMERS");

        assertThat(authorities).extracting(a -> a.getAuthority())
            .containsExactly("ROLE_REALM_CUSTOMERS");
    }
}
