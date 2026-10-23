package tz.co.nlolo.lifeplatform.underwriting.api;

import java.math.BigDecimal;
import java.time.YearMonth;

/**
 * One month's decisions of every outcome together, with the calendar days from opening to decision ADDED over them,
 * unrounded (2026-10-23) -- so a caller can average any span of months exactly rather than averaging rounded averages.
 */
public record DecisionTurnaround(YearMonth month, long cases, BigDecimal totalDays) {}
