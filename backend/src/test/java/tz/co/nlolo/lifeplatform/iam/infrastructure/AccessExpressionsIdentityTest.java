package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the platform imports at a staff member's first sign-in (2026-11-19, the Authentik migration): the login's
 * job_roles claim -- Authentik never turns job roles into authorities -- and, for the tests that sign requests with
 * ROLE_<job> authorities directly, those too.
 */
class AccessExpressionsIdentityTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String SUBJECT = "6da1ab42-33ff-4d51-aceb-c5af26cdb3fe";

    @BeforeEach
    void tenant() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private static Jwt.Builder token() {
        return Jwt.withTokenValue("t").header("alg", "none").subject(SUBJECT).claim("roles", List.of("staff"))
            .claim("preferred_username", "staff.underwriter").claim("name", "Halima Underwriter")
            .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
    }

    private static final List<GrantedAuthority> STAFF_ONLY = List.of(new SimpleGrantedAuthority("ROLE_REALM_STAFF"));

    @Test
    void theJobRolesClaimIsWhatFirstSignInImports() {
        Jwt jwt = token().claim("job_roles", List.of("UNDERWRITER", "SENIOR_UNDERWRITER")).build();

        assertThat(AccessExpressions.identityOf(jwt, STAFF_ONLY).tokenRoles())
            .containsExactlyInAnyOrder("UNDERWRITER", "SENIOR_UNDERWRITER");
    }

    @Test
    void aLoginWithoutJobRolesImportsNone() {
        assertThat(AccessExpressions.identityOf(token().build(), STAFF_ONLY).tokenRoles()).isEmpty();
    }

    @Test
    void aTestsRoleAuthoritiesStillCount() {
        List<GrantedAuthority> signedByATest = List.of(new SimpleGrantedAuthority("ROLE_REALM_STAFF"),
            new SimpleGrantedAuthority("ROLE_FINANCE_OFFICER"));

        assertThat(AccessExpressions.identityOf(token().build(), signedByATest).tokenRoles())
            .containsExactly("FINANCE_OFFICER");
    }

    @Test
    void theIdentityCarriesTheTokensSubjectAndNames() {
        var identity = AccessExpressions.identityOf(token().build(), STAFF_ONLY);

        assertThat(identity.userId()).isEqualTo(UUID.fromString(SUBJECT));
        assertThat(identity.username()).isEqualTo("staff.underwriter");
        assertThat(identity.displayName()).isEqualTo("Halima Underwriter");
        assertThat(identity.tenantId()).isEqualTo(TENANT);
    }
}
