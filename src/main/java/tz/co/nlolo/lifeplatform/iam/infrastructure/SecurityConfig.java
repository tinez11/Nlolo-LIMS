package tz.co.nlolo.lifeplatform.iam.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tz.co.nlolo.lifeplatform.payment.infrastructure.MobileMoneyHmacFilter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Multi-issuer JWT resource server, one AuthenticationManager per Keycloak
 * realm from docs/04-api-contracts.md §3. Every authenticated request gets a
 * synthetic ROLE_REALM_<X> authority (used by @PreAuthorize checks that need
 * "any token from this realm" -- customers/agents/regulators have no specific
 * role names defined anywhere in the Phase 0 docs, only staff does) plus a
 * ROLE_<Y> authority per Keycloak realm_access.roles entry (staff's six roles).
 *
 * Decoders are constructed lazily (see lazyDecoder below): NimbusJwtDecoder.
 * withIssuerLocation(...).build() makes an EAGER HTTP call to the issuer's
 * OIDC discovery endpoint. If that ran at bean-creation time, the whole app
 * context would fail to start whenever Keycloak's realm import hasn't
 * finished yet when `app` boots (infra/docker-compose.yml's app service
 * depends_on keycloak with condition: service_started, not service_healthy)
 * -- exactly the kind of boot-time crash M0's Task 10 had to fix once already
 * for a different dependency. Deferring the discovery call to first actual
 * token validation avoids re-introducing that failure mode.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public AuthenticationManagerResolver<HttpServletRequest> issuerAuthenticationManagerResolver(
            @Value("${app.security.issuers.customers}") String customersIssuer,
            @Value("${app.security.issuers.agents}") String agentsIssuer,
            @Value("${app.security.issuers.staff}") String staffIssuer,
            @Value("${app.security.issuers.regulators}") String regulatorsIssuer) {

        Map<String, AuthenticationManager> managersByIssuer = Map.of(
            customersIssuer, authenticationManagerFor(customersIssuer, "CUSTOMERS"),
            agentsIssuer, authenticationManagerFor(agentsIssuer, "AGENTS"),
            staffIssuer, authenticationManagerFor(staffIssuer, "STAFF"),
            regulatorsIssuer, authenticationManagerFor(regulatorsIssuer, "REGULATORS")
        );
        return new JwtIssuerAuthenticationManagerResolver(managersByIssuer::get);
    }

    private AuthenticationManager authenticationManagerFor(String issuer, String realmMarker) {
        JwtDecoder decoder = lazyDecoder(issuer);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> authoritiesFor(jwt, realmMarker));
        JwtAuthenticationProvider provider = new JwtAuthenticationProvider(decoder);
        provider.setJwtAuthenticationConverter(converter);
        return provider::authenticate;
    }

    private static JwtDecoder lazyDecoder(String issuer) {
        return new JwtDecoder() {
            private volatile JwtDecoder delegate;

            @Override
            public Jwt decode(String token) {
                JwtDecoder current = delegate;
                if (current == null) {
                    synchronized (this) {
                        current = delegate;
                        if (current == null) {
                            current = delegate = NimbusJwtDecoder.withIssuerLocation(issuer).build();
                        }
                    }
                }
                return current.decode(token);
            }
        };
    }

    @SuppressWarnings("unchecked")
    static Collection<GrantedAuthority> authoritiesFor(Jwt jwt, String realmMarker) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_REALM_" + realmMarker));

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") != null) {
            for (String role : (Collection<String>) realmAccess.get("roles")) {
                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
            }
        }
        return authorities;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> issuerAuthenticationManagerResolver,
            ObjectMapper objectMapper) throws Exception {
        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(issuerAuthenticationManagerResolver))
            .addFilterAfter(new TenantContextFilter(objectMapper), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
