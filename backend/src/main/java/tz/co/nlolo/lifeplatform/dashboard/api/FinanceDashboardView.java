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
 * @param period the months the ranged figures below cover, and the months they are compared with (2026-10-23)
 * @param collected premiums collected over the period, TZS
 * @param collectedBefore the same over the comparison months
 * @param collectedSeries one point per month of the period, every month present (zero when nothing was collected)
 * @param collectedCompareSeries the comparison months, in the same order
 * @param collectedByProductLine the period's collections per product category, largest first, with each one's share
 * @param newPolicies policies issued in the period
 * @param outstanding everything owed as at today in TZS, every aging band together
 */
public record FinanceDashboardView(
    String premiumsMonth, Money premiumsCollected, String premiumsChangePercent,
    String balanceSheetMonth, Money totalAssets, Money totalLiabilities,
    String resultFrom, String resultTo, Money netResult,
    Long journalsAwaitingApproval, Long receiptsOverdue, Long transfersAwaiting, Long arrearsAtLapse,
    List<MonthPoint> premiumsThisYear, List<MonthPoint> premiumsLastYear, String currentMonth,
    List<ReceivablesAging> receivables,
    List<ActivityItem> recentActivity,
    Period period, Money collected, Money collectedBefore, String collectedChangePercent,
    List<MonthPoint> collectedSeries, List<MonthPoint> collectedCompareSeries, List<Share> collectedByProductLine,
    Long newPolicies, Long newPoliciesBefore, String newPoliciesChangePercent, List<CountPoint> newPoliciesSeries,
    Money outstanding) {

    public record MonthPoint(String month, Money amount) {}

    /**
     * What is owed in one currency, aged into bands (phase 2, 2026-10-15).
     *
     * <p>One entry per currency, never added across. Every band is present even when empty: unlike an absent month
     * in a series, an empty band means "nothing is this overdue", which is the answer a reader came for.
     */
    public record ReceivablesAging(String currency, List<Bucket> buckets) {

        /** @param outstanding the invoice amount less what has been paid -- a part-paid invoice owes the remainder */
        public record Bucket(String name, long cases, Money outstanding) {}
    }
}
