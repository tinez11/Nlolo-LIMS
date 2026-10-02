package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * One cell of a fixed-term deposit's grid: deposits from {@code minAmount} (up to the next band's
 * start) over {@code termMonths} earn {@code ratePercent} FOR THE TERM -- not a yearly rate.
 */
public record DepositRateRow(BigDecimal minAmount, int termMonths, BigDecimal ratePercent) {}
