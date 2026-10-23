package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.YearMonth;

/** How many of something happened in one civil month -- policies issued, for the dashboards (2026-10-23). */
public record MonthlyCount(YearMonth month, long count) {}
