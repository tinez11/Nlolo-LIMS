package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// Same-package test: SecurityConfig.authoritiesFor is package-private.
// Authentik (2026-11-19): the workspaces arrive in a plain `roles` claim; job roles never become authorities -- the
// platform's iam tables hold them, and the token's job_roles claim is read once, at a staff member's first sign-in.
class SecurityConfigTest {

    private static Jwt token(List<String> roles, List<String> jobRoles) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").claim("roles", roles)
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (!jobRoles.isEmpty()) b.claim("job_roles", jobRoles);
        return b.build();
    }

    private static Jwt token(String... roles) {
        return token(List.of(roles), List.of());
    }

    private static List<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void staffGetsTheWorkspaceMarkerOnly() {
        Jwt staff = token(List.of("staff"), List.of("ADMIN", "UNDERWRITER"));
        assertThat(names(SecurityConfig.authoritiesFor(staff, null))).containsExactly("ROLE_REALM_STAFF");
        assertThat(names(SecurityConfig.authoritiesFor(staff, "staff"))).containsExactly("ROLE_REALM_STAFF");
    }

    @Test
    void agentAndCustomerGetOneMarkerEach() {
        Jwt both = token("agent", "customer");
        assertThat(names(SecurityConfig.authoritiesFor(both, "agent"))).containsExactly("ROLE_REALM_AGENTS");
        assertThat(names(SecurityConfig.authoritiesFor(both, "customer"))).containsExactly("ROLE_REALM_CUSTOMERS");
    }

    @Test
    void regulatorGetsItsMarker() {
        assertThat(names(SecurityConfig.authoritiesFor(token("regulator"), null))).containsExactly("ROLE_REALM_REGULATORS");
    }

    @Test
    void anUnresolvableWorkspaceGrantsNothing() {
        assertThat(SecurityConfig.authoritiesFor(token("agent", "customer"), null)).isEmpty();
        assertThat(SecurityConfig.authoritiesFor(token("customer"), "staff")).isEmpty();
        assertThat(SecurityConfig.authoritiesFor(token(), null)).isEmpty();
    }

    @Test
    void aKeycloakShapedTokenGrantsNothing() {
        Jwt keycloak = Jwt.withTokenValue("t").header("alg", "none")
            .claim("realm_access", Map.of("roles", List.of("staff", "ADMIN")))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(SecurityConfig.authoritiesFor(keycloak, "staff")).isEmpty();
    }

    @Test
    void aTokenWithNoRolesGrantsNothing() {
        Jwt bare = Jwt.withTokenValue("t").header("alg", "none")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(SecurityConfig.authoritiesFor(bare, "customer")).isEmpty();
    }
}
