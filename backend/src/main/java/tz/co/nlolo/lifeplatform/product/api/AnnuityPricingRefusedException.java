package tz.co.nlolo.lifeplatform.product.api;

/**
 * A purchase the version cannot price, in words a person can act on: an unrecorded sex on a BY_SEX
 * form, an age outside the grid, a form or frequency the version does not offer.
 */
public class AnnuityPricingRefusedException extends RuntimeException {
    public AnnuityPricingRefusedException(String message) {
        super(message);
    }
}
