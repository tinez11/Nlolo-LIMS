package tz.co.nlolo.lifeplatform.product.api;

public class DuplicateProductCodeException extends RuntimeException {
    public DuplicateProductCodeException(String productCode) {
        super("A product with code " + productCode + " already exists for this tenant");
    }
}
