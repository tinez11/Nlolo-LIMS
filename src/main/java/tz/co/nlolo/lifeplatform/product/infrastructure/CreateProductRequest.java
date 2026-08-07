package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.ProductCategory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// Mirrors api/openapi/openapi-product.yaml's CreateProductRequest: required
// [productCode, productName, category, defaultCurrency].
public record CreateProductRequest(
    @NotBlank String productCode,
    @NotBlank String productName,
    @NotNull ProductCategory category,
    @NotBlank String defaultCurrency) {}
