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
class SecurityConfigTest {

    private static Jwt token(String... roles) {
        return Jwt.withTokenValue("t").header("alg", "none")
            .claim("realm_access", Map.of("roles", List.of(roles)))
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
    }

    private static List<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void staffGetsTheRealmMarkerAndJobRoles() {
        assertThat(names(SecurityConfig.authoritiesFor(token("staff", "ADMIN", "UNDERWRITER"), null)))
            .containsExactlyInAnyOrder("ROLE_REALM_STAFF", "ROLE_ADMIN", "ROLE_UNDERWRITER");
    }

    @Test
    void theWorkspaceRoleItselfIsNotAnAuthority() {
        assertThat(names(SecurityConfig.authoritiesFor(token("staff"), "staff"))).containsExactly("ROLE_REALM_STAFF");
    }

    @Test
    void agentAndCustomerGetOneMarkerEachAndNoJobRoles() {
        Jwt both = token("agent", "customer", "UNDERWRITER");
        assertThat(names(SecurityConfig.authoritiesFor(both, "agent"))).containsExactly("ROLE_REALM_AGENTS");
        assertThat(names(SecurityConfig.authoritiesFor(both, "customer"))).containsExactly("ROLE_REALM_CUSTOMERS");
    }

    @Test
    void regulatorKeepsItsOwnRole() {
        assertThat(names(SecurityConfig.authoritiesFor(token("regulator", "TIRA_READ_ONLY"), null)))
            .containsExactlyInAnyOrder("ROLE_REALM_REGULATORS", "ROLE_TIRA_READ_ONLY");
    }

    @Test
    void anUnresolvableWorkspaceGrantsNothing() {
        assertThat(SecurityConfig.authoritiesFor(token("agent", "customer"), null)).isEmpty();
        assertThat(SecurityConfig.authoritiesFor(token("customer"), "staff")).isEmpty();
        assertThat(SecurityConfig.authoritiesFor(token(), null)).isEmpty();
    }

    @Test
    void aTokenWithNoRealmAccessGrantsNothing() {
        Jwt bare = Jwt.withTokenValue("t").header("alg", "none")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(SecurityConfig.authoritiesFor(bare, "customer")).isEmpty();
    }
}
