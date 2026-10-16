package tz.co.nlolo.lifeplatform.claims.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One month's settled claims in one currency (2026-10-15).
 *
 * <p>Per currency, never summed across: a total of TZS and USD is a number in no currency. The approved amount is
 * what the platform approved, which is what it has; the amount a bank actually moved belongs to {@code payment}.
 */
public record SettlementMonth(YearMonth month, String currency, long claims, BigDecimal totalApproved) {}
