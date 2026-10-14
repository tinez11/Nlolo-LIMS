package tz.co.nlolo.lifeplatform.reporting.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One month's premium collections in one currency, totalled by the module that owns collections.
 *
 * <p>The same rows the {@code premium-collections} report sums, grouped by month alone, so a chart's points are read
 * from here rather than re-added by whoever draws them. Exists for the role dashboards (2026-10-14), whose rule is that
 * every value on a chart is one a report computed.
 */
public record MonthlyAmount(YearMonth month, String currency, BigDecimal amount) {}
