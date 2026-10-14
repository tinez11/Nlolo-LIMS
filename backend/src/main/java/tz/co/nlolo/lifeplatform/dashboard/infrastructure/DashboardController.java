package tz.co.nlolo.lifeplatform.dashboard.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.application.AgentDashboard;
import tz.co.nlolo.lifeplatform.dashboard.application.ClaimsDashboard;
import tz.co.nlolo.lifeplatform.dashboard.application.FinanceDashboard;
import tz.co.nlolo.lifeplatform.dashboard.application.UnderwritingDashboard;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * One endpoint per role. Each {@code @PreAuthorize} is the SAME expression the module behind the figures applies to
 * its own endpoints, so a dashboard can never show a role figures its own screens would refuse it.
 */
@RestController
public class DashboardController {

    /** The civil date the business runs on. Never UTC: a UTC "today" is yesterday until 03:00 in Dar es Salaam. */
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    private final UnderwritingDashboard underwriting;
    private final ClaimsDashboard claims;
    private final FinanceDashboard finance;
    private final AgentDashboard agent;

    public DashboardController(UnderwritingDashboard underwriting, ClaimsDashboard claims, FinanceDashboard finance,
                               AgentDashboard agent) {
        this.underwriting = underwriting;
        this.claims = claims;
        this.finance = finance;
        this.agent = agent;
    }

    @GetMapping("/dashboards/underwriting")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingDashboardView> underwriting() {
        return ResponseEntity.ok(underwriting.build());
    }

    /**
     * Assessors and managers share it; the queue is chosen by role. Mirrors the claims module's own expression for
     * reading claims, which admits both.
     */
    @GetMapping("/dashboards/claims")
    @PreAuthorize("hasRole('CLAIMS_ASSESSOR') or hasRole('CLAIMS_MANAGER')")
    public ResponseEntity<ClaimsDashboardView> claims(Authentication authentication) {
        boolean manager = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_CLAIMS_MANAGER"::equals);
        return ResponseEntity.ok(claims.build(manager));
    }

    /** The same expression as every finance endpoint (39 of them) and the reports. */
    @GetMapping("/dashboards/finance")
    @PreAuthorize("hasRole('REALM_STAFF') and (hasRole('FINANCE_OFFICER') or hasRole('ADMIN'))")
    public ResponseEntity<FinanceDashboardView> finance() {
        return ResponseEntity.ok(finance.build(LocalDate.now(CIVIL)));
    }

    /**
     * Scoped by the token, never by a parameter: the subject for clients, {@code party_id} for the team -- the two
     * claims the agent's own client and policy screens are scoped on. A token without {@code party_id} is refused,
     * as {@code PolicyController.resolveOwnAgentTeamOrThrow} refuses it.
     */
    @GetMapping("/dashboards/agent")
    @PreAuthorize("hasRole('REALM_AGENTS')")
    public ResponseEntity<AgentDashboardView> agent(@AuthenticationPrincipal Jwt jwt) {
        String partyId = jwt.getClaimAsString("party_id");
        if (partyId == null) {
            throw new AccessDeniedException("Agent token carries no party_id claim");
        }
        return ResponseEntity.ok(agent.build(jwt.getSubject(), UUID.fromString(partyId), LocalDate.now(CIVIL)));
    }
}
