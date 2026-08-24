package tz.co.nlolo.lifeplatform.product.api;

import java.util.UUID;

public class ProductNotFoundException extends RuntimeException {
    public ProductNotFoundException(UUID productId) {
        super("No product found for id " + productId);
    }
}
