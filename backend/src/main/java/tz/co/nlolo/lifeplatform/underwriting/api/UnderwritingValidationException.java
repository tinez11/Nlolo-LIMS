package tz.co.nlolo.lifeplatform.underwriting.api;

/**
 * Thrown when input to an underwriting operation is structurally wrong regardless of the case's
 * current status -- e.g. a disclosure set with no answers in it. Mirrors
 * {@code distribution.api.DistributionValidationException} and
 * {@code claims.api.ClaimValidationException}'s role in their own modules.
 */
public class UnderwritingValidationException extends RuntimeException {
    public UnderwritingValidationException(String message) { super(message); }
}
