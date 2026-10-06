package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.PortfolioCode;
import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// Mirrors api/openapi/openapi-product.yaml's CreateProductRequest: required
// [productCode, productName, category, portfolioCode, defaultCurrency].
public record CreateProductRequest(
    @NotBlank String productCode,
    @NotBlank String productName,
    @NotNull ProductCategory category,
    /** The IFRS 17 portfolio (spec §6) -- required for every new product. */
    @NotNull PortfolioCode portfolioCode,
    @NotBlank String defaultCurrency) {}
