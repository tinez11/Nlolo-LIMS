package tz.co.nlolo.lifeplatform.underwriting.api;

/**
 * A case's channel or branch changed after its policy was issued (IFRS 17 I2): a 409. The policy took them at issue
 * and never changes, so the case may not say otherwise.
 */
public class SaleFixedException extends RuntimeException {
    public SaleFixedException() {
        super("The sale is fixed once the policy is issued");
    }
}
