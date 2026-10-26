package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tz.co.nlolo.lifeplatform.iam.api.AccessApi;

import java.util.List;
import java.util.UUID;

/**
 * What the signed-in staff member may see and do (2026-10-26) -- read by the console to draw only what they can use:
 * the menu, the pages and the buttons. The server checks every act regardless; this only saves showing a button that
 * would be refused.
 */
@RestController
public class AccessController {

    public record RoleDto(String code, String name) {
        static RoleDto from(AccessApi.RoleRef r) { return r == null ? null : new RoleDto(r.code(), r.name()); }
    }

    public record MyAccessDto(UUID userId, String displayName, boolean active, List<RoleDto> roles, RoleDto primaryRole,
                              List<String> permissions) {}

    private final AccessExpressions access;

    public AccessController(AccessExpressions access) {
        this.access = access;
    }

    @GetMapping("/me/access")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<MyAccessDto> myAccess() {
        AccessApi.AccessView view = access.current();
        return ResponseEntity.ok(new MyAccessDto(view.userId(), view.displayName(), view.active(),
            view.roles().stream().map(RoleDto::from).toList(), RoleDto.from(view.primaryRole()),
            List.copyOf(view.permissions())));
    }
}
