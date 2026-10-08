package tz.co.nlolo.lifeplatform.omnichannel.application;

import java.util.UUID;

/**
 * Customer logins in the identity provider (Keycloak's {@code customers} realm). A port, so the invite rules are
 * tested without a Keycloak; the adapter calls the Keycloak Admin API through a service account.
 */
public interface CustomerAccounts {

    /**
     * Creates a login that must set its password at first sign-in, carrying the tenant and party the token will claim.
     *
     * @return the identity provider's id for the user
     * @throws tz.co.nlolo.lifeplatform.omnichannel.api.PortalAccessRefusedException (conflict) if the username is taken
     */
    String create(String username, String email, String displayName, UUID tenantId, UUID partyId);

    /** Emails the user the identity provider's own "set your password" link. */
    void sendSetPasswordLink(String userId);

    /** Sets a one-time password the user must change at first sign-in. */
    void setTemporaryPassword(String userId, String password);

    /** Opens or closes the login. A closed login cannot sign in; its tokens stop being renewed. */
    void setEnabled(String userId, boolean enabled);
}
