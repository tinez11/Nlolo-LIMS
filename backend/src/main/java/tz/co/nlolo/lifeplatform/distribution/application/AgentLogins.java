package tz.co.nlolo.lifeplatform.distribution.application;

import java.util.UUID;

/**
 * Agent logins, as the platform drives them (2026-10-28) -- the port {@code KeycloakAgentLogins} implements, as
 * {@code CustomerAccounts} is for the customer portal. Since 2026-11-06 there is one realm: an agent login is the
 * {@code agent} workspace role on an account, which the same person's portal access may share.
 */
public interface AgentLogins {

    /**
     * Creates a login that must set its password at first sign-in, carrying the tenant and party the token will claim,
     * and gives it the agent workspace. A login with this username that already belongs to this party -- an earlier
     * agent login, or the same person's portal login -- is joined instead.
     *
     * @return the identity provider's id for the user
     * @throws tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException if someone else holds the username
     */
    String create(String username, String email, String displayName, UUID tenantId, UUID partyId);

    /** Emails the identity provider's own "set your password" link. */
    void sendSetPasswordLink(String userId);

    /** Sets a one-time password the agent must change at first sign-in. */
    void setTemporaryPassword(String userId, String password);

    /**
     * Opens or closes the agent workspace on the login. Closing also signs the person out everywhere, but leaves the
     * account and any other workspace it holds (their portal access) in place.
     */
    void setEnabled(String userId, boolean enabled);
}
