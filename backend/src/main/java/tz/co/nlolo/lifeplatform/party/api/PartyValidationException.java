package tz.co.nlolo.lifeplatform.party.api;

/**
 * A registration or amendment the party module refuses on its own terms.
 *
 * <p>Distinct from Bean Validation, which answers "is this message well formed" and cannot
 * answer "is this enough of a person". A caller may send a perfectly shaped request that is
 * still refused — an agent registering a client with no sex recorded sends valid JSON, and the
 * policy it is registered for cannot be priced.
 *
 * <p>422 rather than 400, like {@code DistributionValidationException}: the request was
 * understood and rejected on a rule, not malformed.
 */
public class PartyValidationException extends RuntimeException {
    public PartyValidationException(String message) {
        super(message);
    }
}
