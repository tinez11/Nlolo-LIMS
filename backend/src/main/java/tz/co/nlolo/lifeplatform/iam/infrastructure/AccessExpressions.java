package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.iam.api.AccessApi;
import tz.co.nlolo.lifeplatform.iam.api.Permission;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The {@code @access} bean method security reads: {@code @PreAuthorize("@access.can('policies.surrender')")}
 * (2026-10-26). Staff only -- an agents- or customers-realm token holds no platform permission, and keeps being
 * scoped by its own realm checks.
 *
 * <p>An unknown code is refused and logged rather than thrown: a typo in an annotation must fail closed, and
 * {@code PermissionCatalogueTest} is what stops one reaching main.
 */
@Component("access")
public class AccessExpressions {

    private static final Logger log = LoggerFactory.getLogger(AccessExpressions.class);

    private final AccessApi accessApi;

    public AccessExpressions(AccessApi accessApi) {
        this.accessApi = accessApi;
    }

    public boolean can(String code) {
        Permission permission = Permission.fromCode(code).orElse(null);
        if (permission == null) {
            log.error("@access.can('{}') names no permission in the catalogue -- refused", code);
            return false;
        }
        AccessApi.AccessView view = current();
        return view != null && view.can(permission);
    }

    /** True when the caller holds any one of them. */
    public boolean canAny(String... codes) {
        for (String code : codes) {
            if (can(code)) return true;
        }
        return false;
    }

    /** The signed-in staff member's access, or null for any other caller. */
    public AccessApi.AccessView current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) return null;
        boolean staff = auth.getAuthorities().stream().anyMatch(a -> "ROLE_REALM_STAFF".equals(a.getAuthority()));
        if (!staff) return null;
        return accessApi.accessOf(identityOf(jwt, auth.getAuthorities()));
    }

    /**
     * The roles are read from the authorities SecurityConfig made of the token's {@code realm_access}, not from the claim
     * itself, so a test that signs a request with {@code ROLE_UNDERWRITER} is read exactly as a real token would be.
     */
    static AccessApi.StaffIdentity identityOf(Jwt jwt, Collection<? extends GrantedAuthority> authorities) {
        Set<String> roles = new LinkedHashSet<>();
        for (GrantedAuthority a : authorities) {
            String name = a.getAuthority();
            if (name.startsWith("ROLE_") && !name.startsWith("ROLE_REALM_")) roles.add(name.substring("ROLE_".length()));
        }
        return new AccessApi.StaffIdentity(TenantContext.get(), userIdOf(jwt.getSubject()),
            jwt.getClaimAsString("preferred_username"), jwt.getClaimAsString("name"), jwt.getClaimAsString("email"),
            roles);
    }

    /** Keycloak's subject is a UUID; a test's synthetic one ("staff") is given a stable one of its own. */
    static UUID userIdOf(String subject) {
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException | NullPointerException e) {
            return UUID.nameUUIDFromBytes(String.valueOf(subject).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
