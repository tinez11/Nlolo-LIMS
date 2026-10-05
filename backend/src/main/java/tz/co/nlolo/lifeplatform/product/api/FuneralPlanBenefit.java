package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/** What one plan pays on the death of a life in one role. No row: the plan does not cover that role. */
public record FuneralPlanBenefit(String planCode, FuneralRole role, BigDecimal benefit) {}
