package tz.co.nlolo.lifeplatform.dashboard.api;

/**
 * The months a dashboard's ranged figures cover, and the months they are compared with (2026-10-23). Months are
 * "YYYY-MM", inclusive. The comparison is the same months a year earlier, so a season is compared with itself.
 *
 * @param currentMonth the month in progress: when {@code to} is this month, its figures are to date, not complete
 */
public record Period(String from, String to, String compareFrom, String compareTo, String currentMonth) {}
