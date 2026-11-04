package tz.co.nlolo.lifeplatform.approvals.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.approvals.api.ActionSource;
import tz.co.nlolo.lifeplatform.approvals.api.ActionsView;
import tz.co.nlolo.lifeplatform.approvals.application.Actions;

import java.util.UUID;

/**
 * {@code GET /actions}: the jobs waiting for the viewer -- the bell (2026-11-03). An agent gets their own clients'
 * work; a staff member the kinds their permissions act on.
 */
@RestController
public class ActionsController {

    private final Actions actions;

    public ActionsController(Actions actions) {
        this.actions = actions;
    }

    @GetMapping("/actions")
    @PreAuthorize("hasRole('REALM_AGENTS') or @access.active()")
    public ResponseEntity<ActionsView> actions(@AuthenticationPrincipal Jwt jwt, Authentication authentication) {
        boolean agent = authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
        String partyId = agent ? jwt.getClaimAsString("party_id") : null;
        ActionSource.Viewer viewer = new ActionSource.Viewer(jwt.getSubject(),
            partyId == null ? null : UUID.fromString(partyId));
        return ResponseEntity.ok(actions.forViewer(viewer, agent));
    }
}
