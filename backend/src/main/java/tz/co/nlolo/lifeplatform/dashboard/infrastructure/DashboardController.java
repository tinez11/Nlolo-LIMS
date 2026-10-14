package tz.co.nlolo.lifeplatform.dashboard.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.application.UnderwritingDashboard;

/**
 * One endpoint per role. Each {@code @PreAuthorize} is the SAME expression the module behind the figures applies to
 * its own endpoints, so a dashboard can never show a role figures its own screens would refuse it.
 */
@RestController
public class DashboardController {

    private final UnderwritingDashboard underwriting;

    public DashboardController(UnderwritingDashboard underwriting) {
        this.underwriting = underwriting;
    }

    @GetMapping("/dashboards/underwriting")
    @PreAuthorize("hasRole('UNDERWRITER')")
    public ResponseEntity<UnderwritingDashboardView> underwriting() {
        return ResponseEntity.ok(underwriting.build());
    }
}
