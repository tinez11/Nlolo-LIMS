package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;

/**
 * Claims of one type settled over a span of months, in one currency (2026-10-23, the dashboard's "paid by cause").
 * Per currency, never summed across.
 */
public record SettlementByType(String claimType, String currency, long claims, BigDecimal totalApproved) {}
