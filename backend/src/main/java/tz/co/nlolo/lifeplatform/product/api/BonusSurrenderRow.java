package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/** Per 1,000 of attached bonus, from this many completed policy years until the next row starts. */
public record BonusSurrenderRow(int fromCompletedYears, BigDecimal perMille) {}
