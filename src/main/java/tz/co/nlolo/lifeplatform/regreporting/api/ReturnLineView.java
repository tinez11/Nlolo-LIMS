package tz.co.nlolo.lifeplatform.regreporting.api;

import java.math.BigDecimal;
import java.util.UUID;

/** One line of a generated return. {@code currency} is null for a count and set for a money figure. */
public record ReturnLineView(UUID returnLineId, int lineNo, String lineCode, String label,
                              String metricName, BigDecimal numericValue, String currency) {}
