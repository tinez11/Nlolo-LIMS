package tz.co.nlolo.lifeplatform.dashboard.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.application.ClaimsDashboard;
import tz.co.nlolo.lifeplatform.dashboard.application.FinanceDashboard;
import tz.co.nlolo.lifeplatform.dashboard.application.UnderwritingDashboard;

import java.time.LocalDate;
import java.time.ZoneId;

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

    public DashboardController(UnderwritingDashboard underwriting, ClaimsDashboard claims, FinanceDashboard finance) {
        this.underwriting = underwriting;
        this.claims = claims;
        this.finance = finance;
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
}
