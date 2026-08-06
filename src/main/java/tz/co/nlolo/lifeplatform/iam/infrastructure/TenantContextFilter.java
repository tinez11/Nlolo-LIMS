package tz.co.nlolo.lifeplatform.iam.infrastructure;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 */
public class TenantContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
                String tenantIdClaim = jwt.getClaimAsString("tenant_id");
                if (tenantIdClaim != null) {
                    try {
                        TenantContext.set(UUID.fromString(tenantIdClaim));
                    } catch (IllegalArgumentException ignored) {
                        // Malformed tenant_id claim -- leave TenantContext unset;
                        // downstream TenantContext.get()'s fail-loud guard catches it.
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
