package tz.co.nlolo.lifeplatform.claims.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * @param deceasedPartyId which life died, on a joint-life annuity (product step 5) -- the annuitant or
 *                        the joint life. Null everywhere else, meaning the policy's life assured.
 *                        Stored inside the claim's JSONB details, so it needs no migration.
 * @param loanStatement on credit life, what the borrower still owed the lender on the day (2026-10-30); null on
 *                      every other product
 */
public record DeathClaimDetails(String causeOfDeath, String placeOfDeath,
                                 LocalDate dateOfDeath, String attendingPhysician, UUID deceasedPartyId,
                                 LoanStatement loanStatement)
        implements ClaimDetails {
    /** A joint annuity's death: which life died. */
    public DeathClaimDetails(String causeOfDeath, String placeOfDeath, LocalDate dateOfDeath, String attendingPhysician,
                             UUID deceasedPartyId) {
        this(causeOfDeath, placeOfDeath, dateOfDeath, attendingPhysician, deceasedPartyId, null);
    }


    /** Every death claim but a joint annuity's: the life assured died. */
    public DeathClaimDetails(String causeOfDeath, String placeOfDeath, LocalDate dateOfDeath, String attendingPhysician) {
        this(causeOfDeath, placeOfDeath, dateOfDeath, attendingPhysician, null, null);
    }

    @Override public ClaimType claimType() { return ClaimType.DEATH; }
}
