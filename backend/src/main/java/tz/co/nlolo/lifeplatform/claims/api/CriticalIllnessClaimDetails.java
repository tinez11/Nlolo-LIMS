package tz.co.nlolo.lifeplatform.claims.api;

import java.time.LocalDate;

public record CriticalIllnessClaimDetails(String diagnosis, LocalDate diagnosisDate, String icdCode)
        implements ClaimDetails {
    @Override public ClaimType claimType() { return ClaimType.CRITICAL_ILLNESS; }
}
