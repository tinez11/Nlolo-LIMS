package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;

/**
 * The finance officer's dashboard. Money figures are report values; counts are one status each. Months are "YYYY-MM".
 *
 * @param premiumsMonth the last COMPLETE month, the one {@code premiumsCollected} is for
 * @param premiumsChangePercent {@code premiumsMonth} against the month before, one decimal place, signed; null when
 *     either month is missing or the earlier one is zero -- there is no change against nothing
 * @param balanceSheetMonth the month the balance sheet runs through (the current month)
 * @param resultFrom the first month of the year-to-date income statement
 * @param resultTo the last month of it (the current month)
 * @param premiumsThisYear one point per month of the current year that has collections, oldest first
 * @param premiumsLastYear the same for the previous calendar year
 * @param currentMonth the month in progress, so the console draws it as "to date" rather than as a complete month
 * @param recentActivity this module's latest events, newest first -- what happened, never who; empty when unreadable
 */
public record FinanceDashboardView(
    String premiumsMonth, Money premiumsCollected, String premiumsChangePercent,
    String balanceSheetMonth, Money totalAssets, Money totalLiabilities,
    String resultFrom, String resultTo, Money netResult,
    Long journalsAwaitingApproval, Long receiptsOverdue, Long transfersAwaiting, Long arrearsAtLapse,
    List<MonthPoint> premiumsThisYear, List<MonthPoint> premiumsLastYear, String currentMonth,
    List<ActivityItem> recentActivity) {

    public record MonthPoint(String month, Money amount) {}
}
