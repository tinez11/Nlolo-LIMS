package tz.co.nlolo.lifeplatform.claims.api;

import java.time.LocalDate;

public record DeathClaimDetails(String causeOfDeath, String placeOfDeath,
                                 LocalDate dateOfDeath, String attendingPhysician)
        implements ClaimDetails {
    @Override public ClaimType claimType() { return ClaimType.DEATH; }
}
