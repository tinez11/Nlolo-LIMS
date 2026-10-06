package tz.co.nlolo.lifeplatform.reinsurance.api;

/** No bordereau with this id in the caller's tenant (404). */
public class BordereauNotFoundException extends RuntimeException {
    public BordereauNotFoundException(String message) {
        super(message);
    }
}
