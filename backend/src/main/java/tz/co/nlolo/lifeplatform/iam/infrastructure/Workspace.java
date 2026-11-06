package tz.co.nlolo.lifeplatform.iam.infrastructure;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Which part of the platform a request acts in (2026-11-06, universal login). One Keycloak realm holds everyone; the
 * account's workspace roles say which consoles it may open, and the console names the one it is in with the
 * {@code X-Workspace} header. Exactly one workspace per request, so no request is ever both staff and customer.
 */
enum Workspace {
    STAFF("staff", "STAFF"),
    AGENT("agent", "AGENTS"),
    CUSTOMER("customer", "CUSTOMERS"),
    REGULATOR("regulator", "REGULATORS");

    static final String HEADER = "X-Workspace";

    private final String role;
    private final String marker;

    Workspace(String role, String marker) {
        this.role = role;
        this.marker = marker;
    }

    /** The Keycloak realm role, and the header value, that names this workspace. */
    String role() {
        return role;
    }

    /** The suffix of the {@code ROLE_REALM_*} authority every existing check reads. */
    String marker() {
        return marker;
    }

    /** Whether job roles (every realm role that is not a workspace role) count in this workspace. */
    boolean carriesJobRoles() {
        return this == STAFF || this == REGULATOR;
    }

    static Optional<Workspace> ofRole(String role) {
        for (Workspace w : values()) {
            if (w.role.equals(role)) return Optional.of(w);
        }
        return Optional.empty();
    }

    static boolean isWorkspaceRole(String role) {
        return ofRole(role).isPresent();
    }

    sealed interface Resolution {
        record Active(Workspace workspace) implements Resolution {}
        record Ambiguous(Set<Workspace> held) implements Resolution {}
        record NotHeld(String requested) implements Resolution {}
        record NoWorkspace() implements Resolution {}
    }

    static Resolution resolve(Collection<String> roles, String header) {
        Set<Workspace> held = EnumSet.noneOf(Workspace.class);
        for (String role : roles) ofRole(role).ifPresent(held::add);
        if (held.isEmpty()) return new Resolution.NoWorkspace();
        if (header == null || header.isBlank()) {
            return held.size() == 1 ? new Resolution.Active(held.iterator().next()) : new Resolution.Ambiguous(held);
        }
        String requested = header.trim();
        return ofRole(requested).filter(held::contains)
            .<Resolution>map(Resolution.Active::new)
            .orElseGet(() -> new Resolution.NotHeld(requested));
    }
}
