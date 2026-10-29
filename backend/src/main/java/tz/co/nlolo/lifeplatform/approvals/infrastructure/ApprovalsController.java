package tz.co.nlolo.lifeplatform.approvals.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.approvals.api.ApprovalsView;
import tz.co.nlolo.lifeplatform.approvals.application.Approvals;

/**
 * {@code GET /approvals}: what waits for the viewer's signature. Open to any active staff member; what comes back is
 * cut to the permissions they hold, each group by the same permission its own act is gated on.
 */
@RestController
public class ApprovalsController {

    private final Approvals approvals;

    public ApprovalsController(Approvals approvals) {
        this.approvals = approvals;
    }

    @GetMapping("/approvals")
    @PreAuthorize("@access.active()")
    public ResponseEntity<ApprovalsView> approvals(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(approvals.forViewer(jwt.getSubject()));
    }
}
