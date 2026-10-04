package tz.co.nlolo.lifeplatform.claims.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * @param deceasedPartyId which life died, on a joint-life annuity (product step 5) -- the annuitant or
 *                        the joint life. Null everywhere else, meaning the policy's life assured.
 *                        Stored inside the claim's JSONB details, so it needs no migration.
 */
public record DeathClaimDetails(String causeOfDeath, String placeOfDeath,
                                 LocalDate dateOfDeath, String attendingPhysician, UUID deceasedPartyId)
        implements ClaimDetails {

    /** Every death claim but a joint annuity's: the life assured died. */
    public DeathClaimDetails(String causeOfDeath, String placeOfDeath, LocalDate dateOfDeath, String attendingPhysician) {
        this(causeOfDeath, placeOfDeath, dateOfDeath, attendingPhysician, null);
    }

    @Override public ClaimType claimType() { return ClaimType.DEATH; }
}
