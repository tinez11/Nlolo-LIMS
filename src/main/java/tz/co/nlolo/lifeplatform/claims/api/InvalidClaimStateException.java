package tz.co.nlolo.lifeplatform.claims.api;

/** Thrown when a transition method on {@code Claim} is called from a status that is not a legal
 * source state for that transition. Mirrors {@code InvalidPolicyStateException}'s role in policy. */
public class InvalidClaimStateException extends RuntimeException {
    public InvalidClaimStateException(String message) { super(message); }
}
