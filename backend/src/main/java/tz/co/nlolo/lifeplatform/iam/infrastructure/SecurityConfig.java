package tz.co.nlolo.lifeplatform.iam.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Qualifier;
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

    /**
     * {@code mobileMoneyHmacFilter} is injected as {@link Filter} (the generic servlet type), not
     * the concrete {@code payment.infrastructure.MobileMoneyHmacFilter}, resolved by bean name via
     * {@code @Qualifier} instead of by type. This is deliberate: {@code iam} declaring a field of
     * the concrete type would be a real compile-time dependency from this module onto an internal
     * (non-{@code api}) type of {@code payment}, which Spring Modulith's structural verification
     * (ModularityTests) treats as encapsulation breakage. {@code Filter} is a third-party
     * framework type, not part of any module, so this wiring creates no such dependency while
     * still resolving to the exact right bean at runtime -- {@code addFilterBefore} only needs a
     * {@link Filter} for its first argument anyway.
     *
     * <p>Review fix (I6): the {@code permitAll()} rule below references {@code MobileMoneyHmacFilter
     * .CALLBACK_PATH} instead of its own separately-hardcoded literal, so the two can never
     * silently diverge. This DOES import the concrete type, unlike the {@code Filter} bean
     * parameter above -- but only to read a {@code public static final String} compile-time
     * constant, which {@code javac} inlines at this usage site (JLS 13.4.9): the compiled
     * {@code SecurityConfig.class} carries no runtime reference to {@code MobileMoneyHmacFilter}
     * at all, so this does not reintroduce the encapsulation dependency the {@code Filter}/
     * {@code @Qualifier} wiring above was designed to avoid -- confirmed via {@code ModularityTests}
     * after this change, not assumed.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AuthenticationManagerResolver<HttpServletRequest> issuerAuthenticationManagerResolver,
            ObjectMapper objectMapper,
            @Qualifier("mobileMoneyHmacFilter") Filter mobileMoneyHmacFilter) throws Exception {
        http
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize
                // The two health GROUP paths are listed explicitly. management.endpoint.health
                // .probes.enabled=true creates /actuator/health/liveness and /readiness, but
                // neither is matched by the "/actuator/health" literal, so both were 401 -- which
                // made this branch's own deployment guidance ("move probes to 9090") wrong until
                // a review caught it. Two exact literals rather than "/actuator/health/**":
                // a prefix wildcard would also admit per-component paths (/actuator/health/db and
                // friends), and this file's convention throughout is an exact path, never a
                // prefix. Safe to permit: the health body carries no `components` block because
                // management.endpoint.health.show-details is left at its default of `never`.
                .requestMatchers("/actuator/health", "/actuator/health/liveness",
                                 "/actuator/health/readiness", "/actuator/info").permitAll()
                // Prometheus scrapes with no credentials at all (observability/prometheus.yml
                // declares no basic_auth/bearer_token) and cannot obtain or refresh a Keycloak
                // JWT, so the scrape endpoint must be permitAll SOMEWHERE. What bounds it is
                // WHERE it exists, not this rule: application.yml binds actuator to its own port
                // 9090, and infra/docker-compose.yml publishes only 8080 to the host -- so this
                // rule can only ever take effect on a port that is unreachable from outside the
                // Docker network. On 8080 the endpoint is a 404 regardless of what is permitted
                // here, which ActuatorExposureTest asserts directly.
                //
                // A path literal, matching the two rules above, NOT EndpointRequest.to(...).
                // Determined empirically, not by preference: this chain does apply to the
                // management port (a scrape there returns 401 with WWW-Authenticate: Bearer,
                // i.e. this chain's own oauth2ResourceServer entry point, and health/info return
                // 200 via their path rules above) -- but an EndpointRequest matcher did NOT match
                // there and left the scrape 401. Scoped to this ONE path, deliberately not a
                // prefix wildcard and not toAnyEndpoint(), so that adding an entry to
                // application.yml's exposure allow-list can never also make it anonymously
                // readable. If management.endpoints.web.base-path is ever changed, this literal
                // must change with it -- ActuatorExposureTest fails loudly if it does not.
                .requestMatchers(HttpMethod.GET, "/actuator/prometheus").permitAll()
                // The mobile-money aggregator authenticates with its own HMAC signature scheme,
                // not a Keycloak bearer token (openapi-payment.yaml declares security: [] for
                // this one path). It is permitAll HERE only because mobileMoneyHmacFilter runs
                // in front of it and fails closed -- this is not an unauthenticated endpoint,
                // it is a differently-authenticated one. Scoped to the exact path, never a
                // prefix wildcard.
                .requestMatchers(HttpMethod.POST, MobileMoneyHmacFilter.CALLBACK_PATH).permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2
                .authenticationManagerResolver(issuerAuthenticationManagerResolver))
            .addFilterBefore(mobileMoneyHmacFilter, BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new TenantContextFilter(objectMapper), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
