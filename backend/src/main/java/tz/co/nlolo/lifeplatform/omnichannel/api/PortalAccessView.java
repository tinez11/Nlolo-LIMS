package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.time.Instant;
import java.util.UUID;

/**
 * A client's customer-portal access, as staff see it on the client's page (2026-10-08, customer portal step 1).
 *
 * @param status NOT_INVITED, INVITED (a login exists, never signed in), ACTIVE (has signed in) or REVOKED
 * @param delivery how the first password reached the customer: EMAIL_LINK or TEMPORARY_PASSWORD; null when not invited
 */
public record PortalAccessView(UUID partyId, String status, String username, String delivery, String invitedBy,
                               Instant invitedAt, Instant activatedAt, String revokedBy, Instant revokedAt) {

    public static PortalAccessView notInvited(UUID partyId) {
        return new PortalAccessView(partyId, "NOT_INVITED", null, null, null, null, null, null, null);
    }
}
