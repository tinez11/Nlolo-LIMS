package tz.co.nlolo.lifeplatform.omnichannel.infrastructure;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.AuthentikClient;
import tz.co.nlolo.lifeplatform.AuthentikWorkspaceLogins;
import tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessRefusedException;
import tz.co.nlolo.lifeplatform.omnichannel.application.CustomerAccounts;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Customer portal logins in Authentik (2026-11-19, ticket 04 of the cut-over; replaces the Keycloak adapter): the
 * {@code customer} workspace on the person's one login, which their agent login may share. The id returned and passed
 * back is the login's UUID, the token's {@code sub}.
 */
@Component
public class AuthentikCustomerAccounts implements CustomerAccounts {

    private static final String WORKSPACE = "customer";

    private final AuthentikWorkspaceLogins logins;

    public AuthentikCustomerAccounts(AuthentikWorkspaceLogins logins) {
        this.logins = logins;
    }

    @Override
    public String create(String username, String email, String displayName, UUID tenantId, UUID partyId) {
        try {
            return logins.grant(WORKSPACE, username, email, displayName, tenantId, partyId).toString();
        } catch (AuthentikWorkspaceLogins.TakenBySomeoneElse e) {
            throw new PortalAccessRefusedException("A portal login named " + username + " already exists for someone"
                + " else. Correct the client's phone number, or ask IT to look at that login.", true);
        } catch (AuthentikClient.Refused e) {
            throw unavailable(e);
        }
    }

    /** Email is never a channel here: the invite texts a temporary password, or hands it to staff. */
    @Override
    public void sendSetPasswordLink(String userId) {
        throw new PortalAccessRefusedException("Emailed set-your-password links are not available. Record the client's"
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
                // The customer workspace goes, not the person's login: their agent login stays.
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
