package tz.co.nlolo.lifeplatform.product.api;

/** A family the plan will not cover or cannot price, in words a person can act on. */
public class FuneralQuoteRefusedException extends RuntimeException {
    public FuneralQuoteRefusedException(String message) {
        super(message);
    }
}
