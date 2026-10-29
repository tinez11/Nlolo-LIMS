package tz.co.nlolo.lifeplatform.iam.infrastructure;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    /** One staff member's name, by their token subject (2026-10-27). */
    public record StaffNameDto(String subject, String name) {}

    private final AccessExpressions access;
    private final AccessApi staff;

    public AccessController(AccessExpressions access, AccessApi staff) {
        this.access = access;
        this.staff = staff;
    }

    @GetMapping("/me/access")
    @PreAuthorize("hasRole('REALM_STAFF')")
    public ResponseEntity<MyAccessDto> myAccess() {
        AccessApi.AccessView view = access.current();
        return ResponseEntity.ok(new MyAccessDto(view.userId(), view.displayName(), view.active(),
            view.roles().stream().map(RoleDto::from).toList(), RoleDto.from(view.primaryRole()),
            List.copyOf(view.permissions())));
    }

    /**
     * Who these staff members are, by name (2026-10-27). Screens recorded who reviewed, proposed or approved by token
     * subject and printed it raw -- "reviewed by 1bccfb0a-…". Any working staff member may ask: the names of the
     * people they work with are not a secret, and the answer carries nothing else. A subject the platform does not
     * know is left out, and the console keeps showing it as it is.
     */
    @GetMapping("/staff-names")
    @PreAuthorize("@access.active()")
    public ResponseEntity<List<StaffNameDto>> staffNames(@RequestParam List<String> ids) {
        if (ids.size() > 100) throw new org.springframework.web.server.ResponseStatusException(
            org.springframework.http.HttpStatus.BAD_REQUEST, "At most 100 ids at a time");
        return ResponseEntity.ok(staff.displayNames(ids).entrySet().stream()
            .map(e -> new StaffNameDto(e.getKey(), e.getValue())).toList());
    }
}
