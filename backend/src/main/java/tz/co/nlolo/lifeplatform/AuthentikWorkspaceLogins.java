package tz.co.nlolo.lifeplatform;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The agent and customer workspaces on one login per person (2026-11-19, ticket 04 of the Authentik cut-over), shared by
 * the agent-login and portal adapters. A workspace is a group; a person with both keeps one login in both groups.
 *
 * <ul>
 *   <li><b>Granting</b> creates the login, or joins the same person's existing one: the username taken by a login whose
 *   {@code party_id} is this party. Anyone else's login -- including any login this service did not create, which it
 *   cannot see -- is never taken over.</li>
 *   <li><b>Revoking</b> removes that group only. A login left with no workspace is closed. A login that keeps another
 *   workspace is closed and opened again at once: closing is what ends a person's sessions and refresh tokens in
 *   Authentik, and it needs no wider right than changing the users this service created.</li>
 * </ul>
 */
@Component
public class AuthentikWorkspaceLogins {

    /** The username is someone else's login. */
    public static class TakenBySomeoneElse extends RuntimeException {
        public TakenBySomeoneElse(String username) {
            super("The login " + username + " belongs to someone else");
        }
    }

    private static final Set<String> WORKSPACES = Set.of("staff", "agent", "customer", "regulator");
    /** One folder for both workspaces: the same login may hold either or both. */
    private static final String PATH = "lifeplatform/people";

    private final AuthentikClient authentik;

    public AuthentikWorkspaceLogins(AuthentikClient authentik) {
        this.authentik = authentik;
    }

    /** Gives the person {@code workspace}, on a new login or their existing one; returns the login's UUID. */
    public UUID grant(String workspace, String username, String email, String displayName, UUID tenantId, UUID partyId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("tenant_id", tenantId.toString());
        attributes.put("party_id", partyId.toString());
        // Until a password is issued there is none to sign in with; when there is, it is temporary.
        attributes.put("reset_password", true);
        AuthentikClient.User user;
        try {
            user = authentik.createUser(username, displayName, PATH, email, attributes);
        } catch (AuthentikClient.UsernameTaken taken) {
            AuthentikClient.User existing = authentik.findUserByUsername(username)
                .filter(u -> partyId.toString().equals(String.valueOf(u.attributes().get("party_id"))))
                .orElseThrow(() -> new TakenBySomeoneElse(username));
            authentik.addToGroup(workspace, existing.pk());
            if (!existing.active()) authentik.setActive(existing.pk(), true);
            return existing.uuid();
        }
        try {
            authentik.addToGroup(workspace, user.pk());
        } catch (AuthentikClient.Refused e) {
            // A login that opens nothing would only block a retry as "taken": remove it, then refuse.
            authentik.deleteUser(user.pk());
            throw e;
        }
        return user.uuid();
    }

    /** Gives an existing login {@code workspace} back, and opens the login. */
    public void restore(UUID userId, String workspace) {
        AuthentikClient.User user = authentik.user(userId);
        authentik.addToGroup(workspace, user.pk());
        if (!user.active()) authentik.setActive(user.pk(), true);
    }

    /** Takes {@code workspace} away and signs the person out everywhere; closes the login if no workspace is left. */
    public void revoke(UUID userId, String workspace) {
        AuthentikClient.User user = authentik.user(userId);
        if (user.groups().contains(workspace)) authentik.removeFromGroup(workspace, user.pk());
        List<String> left = authentik.user(userId).groups().stream().filter(WORKSPACES::contains).toList();
        authentik.setActive(user.pk(), false);
        if (!left.isEmpty()) authentik.setActive(user.pk(), true);
    }

    /** A one-time password the person must replace at their next sign-in. */
    public void setTemporaryPassword(UUID userId, String password) {
        int pk = authentik.user(userId).pk();
        authentik.setPassword(pk, password);
        authentik.mergeAttributes(pk, Map.of("reset_password", true));
    }
}
