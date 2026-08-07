package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.FactorType;
import java.math.BigDecimal;

public record RatingFactorRequest(FactorType factorType, String band, BigDecimal multiplier) {}
