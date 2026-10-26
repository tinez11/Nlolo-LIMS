package tz.co.nlolo.lifeplatform.product.api;

import java.util.UUID;

/**
 * @param description what the product is, for staff choosing it (V35, 2026-10-26) -- the latest written, on the product
 *     or its newest version; null when nobody has written one
 */
public record ProductSummaryView(UUID productId, String productCode, String productName, ProductCategory category, ProductStatus status, String defaultCurrency,
                                 /** IFRS 17 portfolio (I2). */
                                 PortfolioCode portfolioCode,
                                 String description) {}
