package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

public record CreateProductRequest(String productCode, String productName, ProductCategory category, String defaultCurrency) {}
