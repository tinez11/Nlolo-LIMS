package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.iam.api.AccessApi;
import tz.co.nlolo.lifeplatform.iam.application.AccessAdministration;
import tz.co.nlolo.lifeplatform.iam.application.AccessAdministration.AccessChange;
import tz.co.nlolo.lifeplatform.iam.application.AccessAdministration.Actor;
import tz.co.nlolo.lifeplatform.iam.application.AccessAdministration.NewUser;
import tz.co.nlolo.lifeplatform.iam.application.AccessAdministration.RoleDraft;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Users and Roles screens (2026-10-26). Reading needs {@code users.view}; changing a person needs
 * {@code users.manage}, changing what a role carries {@code roles.manage}. The rules on top -- not yourself, a second
 * administrator for Administration, never the last administrator -- are {@link AccessAdministration}'s.
 */
@RestController
@RequestMapping("/access")
public class AccessAdminController {

    public record OutcomeDto(boolean applied, UUID changeId) {}


    public record ActiveRequest(boolean active) {}

    private final AccessAdministration admin;
    private final AccessExpressions access;

    public AccessAdminController(AccessAdministration admin, AccessExpressions access) {
        this.admin = admin;
        this.access = access;
    }

    @GetMapping("/permissions")
    @PreAuthorize("@access.can('users.view')")
    public List<AccessAdministration.PermissionInfo> permissions() {
        return admin.catalogue();
    }

    @GetMapping("/roles")
    @PreAuthorize("@access.can('users.view')")
    public List<AccessAdministration.RoleView> roles() {
        return admin.roles();
    }

    @PostMapping("/roles")
    @PreAuthorize("@access.can('roles.manage')")
    public ResponseEntity<AccessAdministration.RoleView> createRole(@RequestBody RoleDraft draft) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.createRole(actor(), draft));
    }

    @PutMapping("/roles/{roleId}")
    @PreAuthorize("@access.can('roles.manage')")
    public OutcomeDto updateRole(@PathVariable UUID roleId, @RequestBody RoleDraft draft) {
        AccessAdministration.Outcome o = admin.updateRole(actor(), roleId, draft);
        return new OutcomeDto(o.applied(), o.changeId());
    }

    @GetMapping("/users")
    @PreAuthorize("@access.can('users.view')")
    public List<AccessAdministration.UserView> users(@RequestParam(required = false) String q) {
        return admin.users(q);
    }

    @GetMapping("/users/{userId}")
    @PreAuthorize("@access.can('users.view')")
    public AccessAdministration.UserDetail user(@PathVariable UUID userId) {
        return admin.user(userId);
    }

    @PostMapping("/users")
    @PreAuthorize("@access.can('users.manage')")
    public ResponseEntity<AccessAdministration.CreatedUser> createUser(@RequestBody NewUser request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(admin.createUser(actor(), request));
    }

    @PutMapping("/users/{userId}/access")
    @PreAuthorize("@access.can('users.manage')")
    public OutcomeDto setAccess(@PathVariable UUID userId, @RequestBody AccessChange change) {
        AccessAdministration.Outcome o = admin.setAccess(actor(), userId, change);
        return new OutcomeDto(o.applied(), o.changeId());
    }

    @PutMapping("/users/{userId}/active")
    @PreAuthorize("@access.can('users.manage')")
    public ResponseEntity<Void> setActive(@PathVariable UUID userId, @RequestBody ActiveRequest request) {
        admin.setActive(actor(), userId, request.active());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{userId}/password-reset")
    @PreAuthorize("@access.can('users.manage')")
    public AccessAdministration.PasswordIssued resetPassword(@PathVariable UUID userId) {
        return admin.resetPassword(actor(), userId);
    }

    @GetMapping("/changes/pending")
    @PreAuthorize("@access.can('users.view')")
    public List<AccessAdministration.ChangeView> pending() {
        return admin.pending();
    }

    @PostMapping("/changes/{changeId}/approval")
    @PreAuthorize("@access.can('users.manage')")
    public ResponseEntity<Void> approve(@PathVariable UUID changeId) {
        admin.decide(actor(), changeId, true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/changes/{changeId}/rejection")
    @PreAuthorize("@access.can('users.manage')")
    public ResponseEntity<Void> reject(@PathVariable UUID changeId, @RequestBody(required = false) Map<String, String> body) {
        admin.decide(actor(), changeId, false);
        return ResponseEntity.noContent().build();
    }

    private Actor actor() {
        AccessApi.AccessView view = access.current();
        return new Actor(view.userId(), view.displayName());
    }
}
