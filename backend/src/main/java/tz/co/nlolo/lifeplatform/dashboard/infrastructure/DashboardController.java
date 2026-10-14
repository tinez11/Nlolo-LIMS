package tz.co.nlolo.lifeplatform.dashboard.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.application.ClaimsDashboard;
import tz.co.nlolo.lifeplatform.dashboard.application.UnderwritingDashboard;

/**
 * One endpoint per role. Each {@code @PreAuthorize} is the SAME expression the module behind the figures applies to
 * its own endpoints, so a dashboard can never show a role figures its own screens would refuse it.
 */
@RestController
public class DashboardController {

    private final UnderwritingDashboard underwriting;
    private final ClaimsDashboard claims;

    public DashboardController(UnderwritingDashboard underwriting, ClaimsDashboard claims) {
        this.underwriting = underwriting;
        this.claims = claims;
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
}
