package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.distribution.api.AgentLoginIssued;
import tz.co.nlolo.lifeplatform.distribution.api.AgentLoginView;
import tz.co.nlolo.lifeplatform.distribution.application.AgentLoginService;

import java.util.UUID;

/**
 * An agent's login to the agent console, on the agent's page (2026-10-28): see it with agents.view; give it, send a
 * new password, or close it with agents.manage -- the permission onboarding an agent already takes.
 */
@RestController
public class AgentLoginController {

    private final AgentLoginService logins;

    public AgentLoginController(AgentLoginService logins) {
        this.logins = logins;
    }

    @GetMapping("/agents/{agentId}/login")
    @PreAuthorize("@access.can('agents.view')")
    public ResponseEntity<AgentLoginView> login(@PathVariable UUID agentId) {
        return ResponseEntity.ok(logins.login(agentId));
    }

    @PostMapping("/agents/{agentId}/login")
    @PreAuthorize("@access.can('agents.manage')")
    public ResponseEntity<AgentLoginIssued> give(@PathVariable UUID agentId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(logins.give(agentId, jwt.getSubject()));
    }

    @PostMapping("/agents/{agentId}/login/password")
    @PreAuthorize("@access.can('agents.manage')")
    public ResponseEntity<AgentLoginIssued> newPassword(@PathVariable UUID agentId) {
        return ResponseEntity.ok(logins.resend(agentId));
    }

    @DeleteMapping("/agents/{agentId}/login")
    @PreAuthorize("@access.can('agents.manage')")
    public ResponseEntity<AgentLoginView> close(@PathVariable UUID agentId, @AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok(logins.close(agentId, jwt.getSubject()));
    }
}
