package tz.co.nlolo.lifeplatform.reporting.api;

/**
 * What a report compares its figures with (2026-11-09, the user: variance against the prior period). The prior period
 * is the same number of months immediately before; the same period last year is the same months a year earlier.
 */
public enum CompareWith { PRIOR_PERIOD, SAME_PERIOD_LAST_YEAR, NONE }
