package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.FactorType;
import java.math.BigDecimal;

/**
 * {@code ageFrom}/{@code ageTo} are required for AGE and forbidden for every other factor
 * type -- see ProductApi.RatingFactorInput. They are what age is rated on; `band` is the
 * label a human reads.
 */
public record RatingFactorRequest(FactorType factorType, String band, BigDecimal multiplier,
                                   Integer ageFrom, Integer ageTo) {}
