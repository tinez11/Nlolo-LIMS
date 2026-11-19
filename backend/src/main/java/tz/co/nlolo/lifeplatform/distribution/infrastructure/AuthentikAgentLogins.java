package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.AuthentikClient;
import tz.co.nlolo.lifeplatform.AuthentikWorkspaceLogins;
import tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException;
import tz.co.nlolo.lifeplatform.distribution.application.AgentLogins;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Agent logins in Authentik (2026-11-19, ticket 04 of the cut-over; replaces the Keycloak adapter): the {@code agent}
 * workspace on the person's one login, which their portal access may share. The id returned and passed back is the
 * login's UUID, the token's {@code sub}.
 */
@Component
public class AuthentikAgentLogins implements AgentLogins {

    private static final String WORKSPACE = "agent";

    private final AuthentikWorkspaceLogins logins;

    public AuthentikAgentLogins(AuthentikWorkspaceLogins logins) {
        this.logins = logins;
    }

    @Override
    public String create(String username, String email, String displayName, UUID tenantId, UUID partyId) {
        try {
            return logins.grant(WORKSPACE, username, email, displayName, tenantId, partyId).toString();
        } catch (AuthentikWorkspaceLogins.TakenBySomeoneElse e) {
            throw new InvalidAgentStateException("An agent login named " + username + " already exists for someone else."
                + " Correct the agent's phone number, or ask IT to look at that login.");
        } catch (AuthentikClient.Refused e) {
            throw unavailable(e);
        }
    }

    /** Email is never a channel here: an agent with no phone is handed a temporary password instead. */
    @Override
    public void sendSetPasswordLink(String userId) {
        throw new InvalidAgentStateException("Emailed set-your-password links are not available. Record the agent's"
            + " phone number, or hand them a temporary password.");
    }

    @Override
    public void setTemporaryPassword(String userId, String password) {
        call(() -> {
            logins.setTemporaryPassword(UUID.fromString(userId), password);
            return null;
        });
    }

    @Override
    public void setEnabled(String userId, boolean enabled) {
        call(() -> {
            if (enabled) {
                logins.restore(UUID.fromString(userId), WORKSPACE);
            } else {
                // The agent workspace goes, not the person's login: their portal access stays.
                logins.revoke(UUID.fromString(userId), WORKSPACE);
            }
            return null;
        });
    }

    private static <T> T call(Supplier<T> action) {
        try {
            return action.get();
        } catch (AuthentikClient.Refused e) {
            throw unavailable(e);
        }
    }

    private static IllegalStateException unavailable(AuthentikClient.Refused e) {
        return new IllegalStateException("The login service refused the request (" + e.status() + "): " + e.getMessage());
    }
}
