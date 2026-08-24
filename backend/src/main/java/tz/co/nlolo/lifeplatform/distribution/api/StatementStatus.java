package tz.co.nlolo.lifeplatform.distribution.api;

/** Matches {@code distribution.commission_statement.status}'s CHECK constraint exactly
 * (db-migrations/distribution/V2 section 6), which widened V1's two-state
 * ({@code PENDING}/{@code PAID}) check to the request/confirm pattern this platform mandates for
 * every money movement -- the direct analogue of {@code claims.api.ClaimStatus}'s
 * {@code SETTLEMENT_REQUESTED}/{@code SETTLED} pair. */
public enum StatementStatus {
    OPEN,
    CLOSED,
    PAYOUT_REQUESTED,
    PAID,
    PAYOUT_FAILED
}
