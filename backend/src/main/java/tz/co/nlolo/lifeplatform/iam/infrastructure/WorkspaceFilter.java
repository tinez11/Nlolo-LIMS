package tz.co.nlolo.lifeplatform.iam.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Answers a signed-in request whose workspace cannot be resolved (2026-11-06): several workspaces and no
 * {@code X-Workspace} (400), a workspace the account does not hold (403), or no workspace at all (403). Without this
 * such a request would carry no authorities and fail later with a bare 403 that says nothing. Runs before Spring MVC,
 * so it writes its own ProblemDetail with a traceId, as {@link TenantContextFilter} does.
 */
class WorkspaceFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    WorkspaceFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            Workspace.Resolution resolution =
                Workspace.resolve(SecurityConfig.realmRoles(jwt), request.getHeader(Workspace.HEADER));
            switch (resolution) {
                case Workspace.Resolution.Active ignored -> { }
                case Workspace.Resolution.Ambiguous ambiguous -> {
                    write(response, HttpStatus.BAD_REQUEST, "WORKSPACE_REQUIRED", "This account opens more than one"
                        + " workspace: send " + Workspace.HEADER + " naming one of " + ambiguous.held().stream()
                        .map(Workspace::role).sorted().collect(Collectors.joining(", ")) + ".");
                    return;
                }
                case Workspace.Resolution.NotHeld notHeld -> {
                    write(response, HttpStatus.FORBIDDEN, "WORKSPACE_NOT_HELD",
                        "This account does not open the " + notHeld.requested() + " workspace.");
                    return;
                }
                case Workspace.Resolution.NoWorkspace ignored -> {
                    write(response, HttpStatus.FORBIDDEN, "NO_WORKSPACE",
                        "This account has no access yet. Contact the insurer.");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }

    private void write(HttpServletResponse response, HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("errorCode", code);
        // traceId is required on every ProblemDetails response (docs/04-api-contracts.md §2).
        problem.setProperty("traceId", UUID.randomUUID().toString());
        response.setStatus(status.value());
        response.setContentType("application/problem+json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }
}
