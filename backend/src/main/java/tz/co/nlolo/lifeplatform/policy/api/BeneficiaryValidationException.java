package tz.co.nlolo.lifeplatform.policy.api;

/** Mapped to 422 Unprocessable Entity -- exactly-one-of(partyId, freeformDesignee) or
 * shares-sum-to-100 violated (Global Constraints: hand-written, not schema-generated). */
public class BeneficiaryValidationException extends RuntimeException {
    public BeneficiaryValidationException(String message) {
        super(message);
    }
}
