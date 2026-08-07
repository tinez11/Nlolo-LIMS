package tz.co.nlolo.lifeplatform.product.api;

import java.util.UUID;

public record ProductSummaryView(UUID productId, String productCode, String productName, ProductCategory category, ProductStatus status, String defaultCurrency) {}
