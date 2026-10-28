package tz.co.nlolo.lifeplatform.distribution.application;

import java.util.UUID;

/**
 * The agents realm's logins, as the platform drives them (2026-10-28) -- the port {@code KeycloakAgentLogins}
 * implements, as {@code CustomerAccounts} is for the customer portal.
 */
public interface AgentLogins {

    /**
     * Creates a login that must set its password at first sign-in, carrying the tenant and party the token will claim.
     * A login with this username that already belongs to this party is reused.
     *
     * @return the identity provider's id for the user
     * @throws tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException if someone else holds the username
     */
    String create(String username, String email, String displayName, UUID tenantId, UUID partyId);

    /** Emails the identity provider's own "set your password" link. */
    void sendSetPasswordLink(String userId);

    /** Sets a one-time password the agent must change at first sign-in. */
    void setTemporaryPassword(String userId, String password);

    /** Opens or closes the login; closing also signs the agent out everywhere. */
    void setEnabled(String userId, boolean enabled);
}
