package tz.co.nlolo.lifeplatform.policy.api;

/** Mapped to 409 Conflict by policy.infrastructure.PolicyExceptionHandler -- see Global
 * Constraints for why this is a dedicated type rather than a bare IllegalStateException. */
public class InvalidPolicyStateException extends RuntimeException {
    public InvalidPolicyStateException(String message) {
        super(message);
    }
}
