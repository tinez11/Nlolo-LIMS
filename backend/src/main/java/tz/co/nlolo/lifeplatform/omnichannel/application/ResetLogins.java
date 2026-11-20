package tz.co.nlolo.lifeplatform.omnichannel.application;

import java.util.Optional;
import java.util.UUID;

/**
 * The logins a password reset may reach (2026-11-19, Authentik cut-over ticket 07): an agent's or customer's, found by
 * the phone number that is its username. A port, so the reset rules are tested without Authentik.
 */
public interface ResetLogins {

    /** The login and the party it belongs to. */
    record ResetLogin(UUID userId, UUID partyId) {}

    /**
     * The open agent or customer login whose username is {@code phone}; empty for anything else -- no login, a closed
     * one, a staff login (staff can't reset their own password), or one with no party.
     */
    Optional<ResetLogin> find(String phone);

    /** A one-time link, valid 15 minutes, that lets the person choose a new password; never texted, only followed. */
    String recoveryLink(UUID userId);
}
