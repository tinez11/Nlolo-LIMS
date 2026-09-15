package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.BenefitCalculationMethod;
import tz.co.nlolo.lifeplatform.product.api.BenefitType;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Mirrors {@code openapi-product.yaml}'s benefit item.
 *
 * <p>{@code calculationMethod} is an enum as of V13, so an unknown spelling is a 400 at the edge
 * rather than a row reading {@code untill death}. {@code percent} and {@code flatAmount} are
 * optional here and constrained by {@code BenefitDefinition}'s own shape rule — exactly one, or
 * neither, depending on the method.
 */
public record BenefitRequest(
    @NotNull BenefitType benefitType,
    @NotNull BenefitCalculationMethod calculationMethod,
    BigDecimal percent,
    BigDecimal flatAmount) {}
