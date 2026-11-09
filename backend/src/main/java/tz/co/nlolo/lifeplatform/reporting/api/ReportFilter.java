package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Every filter any report takes. Which date fields a report needs is its DateFilterKind; the rest must be
 * null (ReportFilters enforces both).
 *
 * @param monthFrom   the accounting period a MONTH report runs for, or the first of a MONTH_RANGE
 * @param monthTo     the last period of a MONTH_RANGE; null on a MONTH report
 * @param accountCode restricts a ledger report to one chart account, four digits; null for every account
 */
public record ReportFilter(LocalDate asOf, LocalDate compareAsOf, LocalDate from, LocalDate to, YearMonth cohortFrom,
                           YearMonth cohortTo, UUID productId, String productCategory, UUID agentId, String salesChannel,
                           String branchCode, String clientType, GroupBy groupBy,
                           YearMonth monthFrom, YearMonth monthTo, String accountCode, CompareWith compareWith) {

    public static ReportFilter none() {
        return new ReportFilter(null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null, CompareWith.NONE);
    }

    public static ReportFilter asOf(LocalDate asOf) {
        return none().withAsOf(asOf);
    }

    public static ReportFilter period(LocalDate from, LocalDate to) {
        return none().withPeriod(from, to);
    }

    public static ReportFilter cohort(YearMonth from, YearMonth to) {
        return new ReportFilter(null, null, null, null, from, to, null, null, null, null, null, null, null,
            null, null, null, CompareWith.NONE);
    }

    /** One accounting period -- a MONTH report. */
    public static ReportFilter month(YearMonth month) {
        return none().withMonths(month, null);
    }

    /** A span of accounting periods, both ends inclusive -- a MONTH_RANGE report. */
    public static ReportFilter months(YearMonth from, YearMonth to) {
        return none().withMonths(from, to);
    }

    public ReportFilter withAsOf(LocalDate date) {
        return new ReportFilter(date, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode, compareWith);
    }

    public ReportFilter withPeriod(LocalDate start, LocalDate end) {
        return new ReportFilter(asOf, compareAsOf, start, end, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode, compareWith);
    }

    public ReportFilter withCompareAsOf(LocalDate date) {
        return new ReportFilter(asOf, date, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode, compareWith);
    }

    public ReportFilter withGroupBy(GroupBy by) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, by, monthFrom, monthTo, accountCode, compareWith);
    }

    public ReportFilter withClientType(String type) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, type, groupBy, monthFrom, monthTo, accountCode, compareWith);
    }

    public ReportFilter withProductId(UUID id) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, id, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode, compareWith);
    }

    /**
     * The accounting periods this report covers.
     *
     * <p>{@code firstMonth} and {@code lastMonth} rather than {@code from}/{@code to}: those are the record's
     * own LocalDate components, and parameters named for them would shadow the components this method must
     * carry through unchanged -- a swap that compiles and silently trades a report's dates for its months.
     */
    public ReportFilter withMonths(YearMonth firstMonth, YearMonth lastMonth) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory,
            agentId, salesChannel, branchCode, clientType, groupBy, firstMonth, lastMonth, accountCode, compareWith);
    }

    public ReportFilter withAccountCode(String code) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory,
            agentId, salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, code, compareWith);
    }

    public ReportFilter withCompareWith(CompareWith with) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory,
            agentId, salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode,
            with == null ? CompareWith.NONE : with);
    }

    /**
     * For a report on a date (2026-11-09, batch 4): the date it is compared with -- a month earlier, or a year earlier.
     * Null when it compares with nothing.
     */
    public java.time.LocalDate comparisonDate() {
        if (compareWith == null || compareWith == CompareWith.NONE || asOf == null) return null;
        return compareWith == CompareWith.SAME_PERIOD_LAST_YEAR ? asOf.minusYears(1) : asOf.minusMonths(1);
    }

    /**
     * For a report over a span of days (2026-11-09, batch 4): the span it is compared with -- the same length
     * immediately before, or the same days a year earlier. A span of whole months moves by whole months, so October is
     * compared with September, not with 31 August to 30 September. Null when it compares with nothing.
     */
    public java.time.LocalDate[] comparisonSpan() {
        if (compareWith == null || compareWith == CompareWith.NONE || from == null || to == null) return null;
        if (compareWith == CompareWith.SAME_PERIOD_LAST_YEAR) {
            return new java.time.LocalDate[] { from.minusYears(1), to.minusYears(1) };
        }
        boolean wholeMonths = from.getDayOfMonth() == 1 && to.equals(YearMonth.from(to).atEndOfMonth());
        if (wholeMonths) {
            long months = java.time.temporal.ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;
            return new java.time.LocalDate[] { from.minusMonths(months), YearMonth.from(to).minusMonths(months).atEndOfMonth() };
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        return new java.time.LocalDate[] { from.minusDays(days), from.minusDays(1) };
    }

    /** Whether this run compares with another span of months (2026-11-09, the reports redesign). */
    public boolean compares() {
        return compareWith != null && compareWith != CompareWith.NONE && monthFrom != null;
    }

    /**
     * The months this run is compared with: the same number of months immediately before, or the same months a year
     * earlier. Null when it compares with nothing. A MONTH report's single month is a one-month span.
     */
    public YearMonth[] comparisonMonths() {
        if (!compares()) {
            return null;
        }
        YearMonth last = monthTo != null ? monthTo : monthFrom;
        long length = java.time.temporal.ChronoUnit.MONTHS.between(monthFrom, last) + 1;
        return compareWith == CompareWith.SAME_PERIOD_LAST_YEAR
            ? new YearMonth[] { monthFrom.minusYears(1), last.minusYears(1) }
            : new YearMonth[] { monthFrom.minusMonths(length), last.minusMonths(length) };
    }

    public GroupBy groupByOr(GroupBy fallback) {
        return groupBy != null ? groupBy : fallback;
    }
}
