package tz.co.nlolo.lifeplatform.claims.api;

import java.time.LocalDate;

public record MaturityClaimDetails(LocalDate maturityDate) implements ClaimDetails {
    @Override public ClaimType claimType() { return ClaimType.MATURITY; }
}
