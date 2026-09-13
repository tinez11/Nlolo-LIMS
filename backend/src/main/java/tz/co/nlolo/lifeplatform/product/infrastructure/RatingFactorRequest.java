package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.FactorType;
import java.math.BigDecimal;

/**
 * {@code ageFrom}/{@code ageTo} are required for AGE and forbidden for every other factor
 * type -- see ProductApi.RatingFactorInput. They are what age is rated on; `band` is the
 * label a human reads.
 *
 * <p>{@code sumAssuredFrom}/{@code sumAssuredTo} are the same thing for SUM_ASSURED_BAND (V9),
 * and carrying them here is what makes the fix real: a band was matched by exact string against
 * three values hardcoded in underwriting, so an author's band could never match. If this wire
 * record did not carry the bounds, every publish from the console would still land rows with no
 * bounds at all -- which is the defect, arriving through a screen that appears to have fixed it.
 */
public record RatingFactorRequest(FactorType factorType, String band, BigDecimal multiplier,
                                   Integer ageFrom, Integer ageTo,
                                   BigDecimal sumAssuredFrom, BigDecimal sumAssuredTo) {}
