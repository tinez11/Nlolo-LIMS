package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DisabilityClaimDetails(String disabilityType, LocalDate onsetDate,
                                      boolean permanent, BigDecimal impairmentPercent)
        implements ClaimDetails {
    @Override public ClaimType claimType() { return ClaimType.DISABILITY; }
}
