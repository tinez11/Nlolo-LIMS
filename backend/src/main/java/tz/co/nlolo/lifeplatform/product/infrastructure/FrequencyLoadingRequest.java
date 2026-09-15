package tz.co.nlolo.lifeplatform.product.infrastructure;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

/**
 * Mirrors {@code openapi-product.yaml}'s {@code FrequencyLoading}.
 *
 * <p>Both fields optional: an absent block, and a block of nulls, both mean an unloaded version —
 * one that charges a monthly payer the same total as an annual payer, which is a real pricing
 * decision rather than missing data.
 */
public record FrequencyLoadingRequest(
    @DecimalMin("0") @DecimalMax("100") BigDecimal monthlyPercent,
    @DecimalMin("0") @DecimalMax("100") BigDecimal quarterlyPercent) {}
