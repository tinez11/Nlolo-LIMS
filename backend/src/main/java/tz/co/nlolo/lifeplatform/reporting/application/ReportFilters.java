package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.util.Set;
import java.util.regex.Pattern;

/** A report gets exactly the date fields its kind names -- a stray one is refused, not silently ignored, so a
 * caller never believes a filter was applied when it was not. */
final class ReportFilters {
    private ReportFilters() {}

    private static final Set<String> CLIENT_TYPES = Set.of("INDIVIDUAL", "CORPORATE", "GROUP");

    /** The posting guide's nine classes, as the chart of accounts writes them. */
    private static final Pattern ACCOUNT_CODE = Pattern.compile("^[1-9]\\d{3}$");

    static void validate(ReportDefinitionView d, ReportFilter f) {
        String key = d.key();
        switch (d.dateFilterKind()) {
            case AS_OF -> {
                require(f.asOf() != null, "asOf is required for " + key);
                refuse(f.from() != null || f.to() != null, "from/to do not apply to " + key + "; it takes asOf");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
                refuseMonths(key, f);
            }
            case PERIOD -> {
                require(f.from() != null && f.to() != null, "from and to are required for " + key);
                refuse(f.from().isAfter(f.to()), "from must not be after to");
                refuse(f.asOf() != null, "asOf does not apply to " + key + "; it takes from and to");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
                refuseMonths(key, f);
            }
            case COHORT -> {
                require(f.cohortFrom() != null && f.cohortTo() != null, "cohortFrom and cohortTo are required for " + key);
                refuse(f.cohortFrom().isAfter(f.cohortTo()), "cohortFrom must not be after cohortTo");
                refuse(f.asOf() != null || f.from() != null || f.to() != null,
                    "asOf/from/to do not apply to " + key + "; it takes cohortFrom and cohortTo");
                refuseMonths(key, f);
            }
            case AS_OF_AND_PERIOD -> {
                require(f.asOf() != null && f.from() != null && f.to() != null, "asOf, from and to are required for " + key);
                refuse(f.from().isAfter(f.to()), "from must not be after to");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
                refuseMonths(key, f);
            }
            case MONTH -> {
                require(f.monthFrom() != null, "monthFrom is required for " + key);
                refuse(f.monthTo() != null, "monthTo does not apply to " + key + "; it takes one month");
                refuseDays(key, f, "monthFrom");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
            }
            case MONTH_RANGE -> {
                require(f.monthFrom() != null && f.monthTo() != null, "monthFrom and monthTo are required for " + key);
                refuse(f.monthFrom().isAfter(f.monthTo()), "monthFrom must not be after monthTo");
                refuseDays(key, f, "monthFrom and monthTo");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
            }
        }
        refuse(f.compareAsOf() != null && !d.comparable(), "compareAsOf does not apply to " + key);
        refuse(f.groupBy() != null && !d.groupByOptions().contains(f.groupBy()),
            key + " cannot be grouped by " + f.groupBy());
        refuse(f.clientType() != null && !CLIENT_TYPES.contains(f.clientType()),
            "clientType must be one of " + CLIENT_TYPES);
        refuse(f.accountCode() != null && !ACCOUNT_CODE.matcher(f.accountCode()).matches(),
            "accountCode must be four digits starting 1-9, as the chart of accounts writes them");
    }

    /** A month handed to a report that reads dates is a caller's mistake, not a field to drop quietly. */
    private static void refuseMonths(String key, ReportFilter f) {
        refuse(f.monthFrom() != null || f.monthTo() != null,
            "monthFrom/monthTo do not apply to " + key + "; it takes dates");
    }

    /** A day handed to a ledger report: the ledger is organised by period and has no posting date to match it. */
    private static void refuseDays(String key, ReportFilter f, String takes) {
        refuse(f.asOf() != null || f.from() != null || f.to() != null,
            "asOf/from/to do not apply to " + key + "; it takes " + takes);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ReportParameterException(message);
    }

    private static void refuse(boolean condition, String message) {
        if (condition) throw new ReportParameterException(message);
    }
}
