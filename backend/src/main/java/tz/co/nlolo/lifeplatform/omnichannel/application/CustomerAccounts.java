package tz.co.nlolo.lifeplatform.omnichannel.application;

import java.util.UUID;

/**
 * Customer logins in the identity provider -- since 2026-11-06 the {@code customer} workspace role on an account in the
 * one Keycloak realm, {@code lifeplatform}, which the same person's agent login may share. A port, so the invite rules
 * are tested without a Keycloak; the adapter calls the Keycloak Admin API through a service account.
 */
public interface CustomerAccounts {

    /**
     * Creates a login that must set its password at first sign-in, carrying the tenant and party the token will claim,
     * and gives it the customer workspace. A login with this username that already belongs to this party -- an earlier
     * invite, or the same person's agent login -- is joined instead.
     *
     * @return the identity provider's id for the user
     * @throws tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessRefusedException (conflict) if the username is taken
     */
    String create(String username, String email, String displayName, UUID tenantId, UUID partyId);

    /** Emails the user the identity provider's own "set your password" link. */
    void sendSetPasswordLink(String userId);

    /** Sets a one-time password the user must change at first sign-in. */
    void setTemporaryPassword(String userId, String password);

    /**
     * Opens or closes the customer workspace on the login. Closing signs the person out everywhere, but leaves the
     * account and any other workspace it holds (their agent login) in place.
     */
    void setEnabled(String userId, boolean enabled);
}
