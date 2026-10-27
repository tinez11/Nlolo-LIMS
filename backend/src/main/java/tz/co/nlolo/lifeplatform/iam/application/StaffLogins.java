package tz.co.nlolo.lifeplatform.iam.application;

import java.util.UUID;

/**
 * The login half of a staff member, held by Keycloak's staff realm: created, opened, closed and reset from the Users
 * screen (2026-10-26). The platform holds everything else about them.
 */
public interface StaffLogins {

    record NewLogin(String username, String firstName, String lastName, String email, UUID tenantId,
                    String homeBranch) {}

    /** Creates the login, which must set its own password at first sign-in; returns its id. */
    UUID create(NewLogin login);

    /** The identity provider's own email: a link to set a password, as a portal invite with an email gets. */
    void sendSetPasswordLink(UUID userId);

    void setTemporaryPassword(UUID userId, String password);

    /** Opens or closes the login. Closing also ends every session it holds, so the person is out at once. */
    void setEnabled(UUID userId, boolean enabled);
}
