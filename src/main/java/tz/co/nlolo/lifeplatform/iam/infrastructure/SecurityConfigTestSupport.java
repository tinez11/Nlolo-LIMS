package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;

public final class SecurityConfigTestSupport {
    private SecurityConfigTestSupport() {}

    public static Collection<GrantedAuthority> authoritiesFor(Jwt jwt, String realmMarker) {
        return SecurityConfig.authoritiesFor(jwt, realmMarker);
    }
}
