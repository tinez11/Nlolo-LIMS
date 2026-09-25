package tz.co.nlolo.lifeplatform.policy.api;

/**
 * A group or credit-life product was sent down the single-life issuance path.
 *
 * <p>Such a policy covers nobody: it has no member schedule, so a group contract is issued with
 * no lives on it and the client page cannot load one. Schemes are issued through their own set-up.
 * Mapped to 422 by policy.infrastructure.PolicyExceptionHandler.
 */
public class NotASingleLifeProductException extends RuntimeException {
    public NotASingleLifeProductException(String category) {
        super("A " + category + " product is not issued as a single-life policy -- set it up as a "
            + ("CREDIT_LIFE".equals(category) ? "credit-life scheme" : "group scheme"));
    }
}
