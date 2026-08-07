package tz.co.nlolo.lifeplatform.iam.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.io.IOException;
import java.util.UUID;

/**
 * Populates TenantContext from the authenticated request's tenant_id JWT
 * claim (docs/04-api-contracts.md line 39: tenant_id is read exclusively
 * from validated JWT claims, never client input). Must run AFTER JWT
 * authentication (registered via addFilterAfter(BearerTokenAuthenticationFilter))
 * so SecurityContextHolder already carries the authenticated Jwt principal.
 * Clears in a finally block -- same ThreadLocal-leak-on-reuse risk as
 * TenantAwareDataSource's pooled-connection RESET fix, except here the
 * "pool" is the servlet container's worker-thread pool.
 *
 * A missing or malformed tenant_id claim is an AUTHORIZATION failure, not a
 * server fault: a token that cannot be attributed to any tenant must never
 * reach the service layer, where it would otherwise surface as a generic 500
 * via TenantContext.get()'s fail-loud IllegalStateException guard. Rejecting
 * it here, at the request boundary, with a 403 and a logged WARN turns a
 * Keycloak misconfiguration (or an attempted attack using tokens without a
 * proper tenant claim) into a clear, attributable auth signal instead of an
 * unexplained 500 spike. The response is written directly to the
 * HttpServletResponse (rather than thrown for @ExceptionHandler to catch)
 * because this filter runs before Spring MVC's dispatch -- @ExceptionHandler
 * advice never sees exceptions thrown from here.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);

    private final ObjectMapper objectMapper;

    public TenantContextFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                UUID tenantId = parseTenantId(jwt.getClaimAsString("tenant_id"));
                if (tenantId == null) {
                    log.warn("Rejecting request with missing or malformed tenant_id claim: subject={}, issuer={}",
                        jwt.getSubject(), jwt.getIssuer());
                    writeTenantClaimMissingResponse(response);
                    return;
                }
                TenantContext.set(tenantId);
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static UUID parseTenantId(String tenantIdClaim) {
        if (tenantIdClaim == null) {
            return null;
        }
        try {
            return UUID.fromString(tenantIdClaim);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void writeTenantClaimMissingResponse(HttpServletResponse response) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
            "Token is missing a valid tenant_id claim");
        problem.setProperty("errorCode", "TENANT_CLAIM_MISSING");
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType("application/problem+json");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
