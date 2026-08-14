package tz.co.nlolo.lifeplatform.distribution.api;

/** Thrown when input to a distribution domain operation is structurally wrong regardless of the
 * entity's current status -- e.g. a blank payout idempotency key, a blank payeeRef, or a
 * {@code CommissionRule} authored with {@code TierType.THRESHOLD_BONUS}. Mirrors
 * {@code claims.api.ClaimValidationException}'s role in claims. */
public class DistributionValidationException extends RuntimeException {
    public DistributionValidationException(String message) { super(message); }
}
