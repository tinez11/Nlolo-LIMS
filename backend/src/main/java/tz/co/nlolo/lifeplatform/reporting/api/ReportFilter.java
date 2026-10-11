package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/** Every filter any report takes. Which date fields a report needs is its DateFilterKind; the rest must be
 * null (ReportFilters enforces both). */
public record ReportFilter(LocalDate asOf, LocalDate compareAsOf, LocalDate from, LocalDate to, YearMonth cohortFrom,
                           YearMonth cohortTo, UUID productId, String productCategory, UUID agentId, String salesChannel,
                           String branchCode, String clientType, GroupBy groupBy) {

    public static ReportFilter none() {
        return new ReportFilter(null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static ReportFilter asOf(LocalDate asOf) {
        return none().withAsOf(asOf);
    }

    public static ReportFilter period(LocalDate from, LocalDate to) {
        return none().withPeriod(from, to);
    }

    public static ReportFilter cohort(YearMonth from, YearMonth to) {
        return new ReportFilter(null, null, null, null, from, to, null, null, null, null, null, null, null);
    }

    public ReportFilter withAsOf(LocalDate date) {
        return new ReportFilter(date, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy);
    }

    public ReportFilter withPeriod(LocalDate start, LocalDate end) {
        return new ReportFilter(asOf, compareAsOf, start, end, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy);
    }

    public ReportFilter withCompareAsOf(LocalDate date) {
        return new ReportFilter(asOf, date, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy);
    }

    public ReportFilter withGroupBy(GroupBy by) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, clientType, by);
    }

    public ReportFilter withClientType(String type) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, productId, productCategory, agentId,
            salesChannel, branchCode, type, groupBy);
    }

    public ReportFilter withProductId(UUID id) {
        return new ReportFilter(asOf, compareAsOf, from, to, cohortFrom, cohortTo, id, productCategory, agentId,
            salesChannel, branchCode, clientType, groupBy);
    }

    public GroupBy groupByOr(GroupBy fallback) {
        return groupBy != null ? groupBy : fallback;
    }
}
