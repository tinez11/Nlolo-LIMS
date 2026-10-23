package tz.co.nlolo.lifeplatform.dashboard.api;

/**
 * A month's mean calendar days for something to happen, one decimal place as a decimal string; null when nothing
 * happened that month -- there is no average of nothing, and zero days would be a claim.
 */
public record DaysPoint(String month, String averageDays) {}
