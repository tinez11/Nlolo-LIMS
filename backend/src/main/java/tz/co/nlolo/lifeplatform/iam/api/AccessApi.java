package tz.co.nlolo.lifeplatform.iam.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who a staff member is on the platform and what they may do (2026-10-26). Keycloak says who signed in; this says
 * what they hold -- read on every request, so a change an administrator makes applies at once, not at the next login.
 */
public interface AccessApi {

    /** The signed-in staff member as their token describes them: enough to recognise them on first sight. */
    record StaffIdentity(UUID tenantId, UUID userId, String username, String displayName, String email,
                         Set<String> tokenRoles) {}

    record RoleRef(String code, String name) {}

    /**
     * What one staff member holds. {@code primaryRole} is the one the console's badge shows beneath their name -- the
     * first they hold in the built-in precedence (Administrator first), or null when they hold none.
     */
    record AccessView(UUID userId, String displayName, boolean active, List<RoleRef> roles, RoleRef primaryRole,
                      Set<String> permissions) {

        public boolean can(Permission permission) {
            return active && permissions.contains(permission.code());
        }
    }

    AccessView accessOf(StaffIdentity identity);

    /**
     * The names of the staff members behind these token subjects, in this tenant (2026-10-27). Screens used to print
     * "proposed by 1bccfb0a-…"; a subject the platform has not seen yet (or one that is not a staff member's) is
     * simply absent from the answer, and the caller keeps the subject.
     */
    java.util.Map<String, String> displayNames(java.util.Collection<String> subjects);
}
