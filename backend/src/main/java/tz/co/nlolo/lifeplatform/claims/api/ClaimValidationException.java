package tz.co.nlolo.lifeplatform.claims.api;

/** Thrown when input to a `Claim` operation is structurally wrong regardless of the claim's
 * current status -- e.g. a non-positive approved amount, or a blank settlement idempotency key. */
public class ClaimValidationException extends RuntimeException {
    public ClaimValidationException(String message) { super(message); }
}
