package tz.co.nlolo.lifeplatform.claims.api;

/**
 * Must match {@code db-migrations/claims/V1__create_claims_schema.sql:12-13}'s CHECK constraint
 * exactly. The legal transitions between these values are enforced in exactly one place --
 * {@code claims.domain.Claim}'s transition methods -- per this plan's header.
 */
public enum ClaimStatus {
    REGISTERED, UNDER_ASSESSMENT, APPROVED, REJECTED, SETTLEMENT_REQUESTED, SETTLED, REOPENED
}
