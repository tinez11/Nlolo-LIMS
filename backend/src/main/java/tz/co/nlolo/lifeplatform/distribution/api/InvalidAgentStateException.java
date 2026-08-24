package tz.co.nlolo.lifeplatform.distribution.api;

/** Thrown when a transition method on a distribution aggregate (e.g. {@code CommissionStatement})
 * is called from a status that is not a legal source state for that transition. Mirrors
 * {@code claims.api.InvalidClaimStateException}'s role in claims. */
public class InvalidAgentStateException extends RuntimeException {
    public InvalidAgentStateException(String message) { super(message); }
}
