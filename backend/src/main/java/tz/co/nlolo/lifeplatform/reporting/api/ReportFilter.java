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
                           YearMonth monthFrom, YearMonth monthTo, String accountCode) {

    public static ReportFilter none() {
        return new ReportFilter(null, null, null, null, null, null, null, null, null, null, null, null, null,
            null, null, null);
    }

    public static ReportFilter asOf(LocalDate asOf) {
        return none().withAsOf(asOf);
    }

    public static ReportFilter period(LocalDate from, LocalDate to) {
        return none().withPeriod(from, to);
    }

    public static ReportFilter cohort(YearMonth from, YearMonth to) {
        return new ReportFilter(null, null, null, null, from, to, null, null, null, null, null, null, null,
            null, null, null);
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
            salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode);
    }

    public ReportFilter withPeriod(LocalDate start, LocalDate end) {
        return new ReportFilter(asOf, compareAsOf, start, end, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode);
    }

    public ReportFilter withCompareAsOf(LocalDate date) {
        return new ReportFilter(asOf, date, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode);
    }

    public ReportFilter withGroupBy(GroupBy by) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, by, monthFrom, monthTo, accountCode);
    }

    public ReportFilter withClientType(String type) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, type, groupBy, monthFrom, monthTo, accountCode);
    }

    public ReportFilter withProductId(UUID id) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, id, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, accountCode);
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
            agentId, salesChannel, branchCode, clientType, groupBy, firstMonth, lastMonth, accountCode);
    }

    public ReportFilter withAccountCode(String code) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory,
            agentId, salesChannel, branchCode, clientType, groupBy, monthFrom, monthTo, code);
    }

    public GroupBy groupByOr(GroupBy fallback) {
        return groupBy != null ? groupBy : fallback;
    }
}
