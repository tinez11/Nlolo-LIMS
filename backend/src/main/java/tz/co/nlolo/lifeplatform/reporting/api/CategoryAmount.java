package tz.co.nlolo.lifeplatform.reporting.api;

import java.math.BigDecimal;

/**
 * Premiums collected for one product category in one currency over a span of months (2026-10-23, the dashboard's
 * product-line share). Per currency, never added across. A collection on a policy the projection does not know is
 * under {@code UNKNOWN} rather than dropped, so the shares still add up to the month's own total.
 */
public record CategoryAmount(String category, String currency, BigDecimal amount) {}
