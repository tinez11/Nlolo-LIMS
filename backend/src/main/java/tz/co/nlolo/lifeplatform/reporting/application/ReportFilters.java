package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.util.Set;

/** A report gets exactly the date fields its kind names -- a stray one is refused, not silently ignored, so a
 * caller never believes a filter was applied when it was not. */
final class ReportFilters {
    private ReportFilters() {}

    private static final Set<String> CLIENT_TYPES = Set.of("INDIVIDUAL", "CORPORATE", "GROUP");

    static void validate(ReportDefinitionView d, ReportFilter f) {
        String key = d.key();
        switch (d.dateFilterKind()) {
            case AS_OF -> {
                require(f.asOf() != null, "asOf is required for " + key);
                refuse(f.from() != null || f.to() != null, "from/to do not apply to " + key + "; it takes asOf");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
            }
            case PERIOD -> {
                require(f.from() != null && f.to() != null, "from and to are required for " + key);
                refuse(f.from().isAfter(f.to()), "from must not be after to");
                refuse(f.asOf() != null, "asOf does not apply to " + key + "; it takes from and to");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
            }
            case COHORT -> {
                require(f.cohortFrom() != null && f.cohortTo() != null, "cohortFrom and cohortTo are required for " + key);
                refuse(f.cohortFrom().isAfter(f.cohortTo()), "cohortFrom must not be after cohortTo");
                refuse(f.asOf() != null || f.from() != null || f.to() != null,
                    "asOf/from/to do not apply to " + key + "; it takes cohortFrom and cohortTo");
            }
            case AS_OF_AND_PERIOD -> {
                require(f.asOf() != null && f.from() != null && f.to() != null, "asOf, from and to are required for " + key);
                refuse(f.from().isAfter(f.to()), "from must not be after to");
                refuse(f.cohortFrom() != null || f.cohortTo() != null, "cohortFrom/cohortTo do not apply to " + key);
            }
        }
        refuse(f.compareAsOf() != null && !d.comparable(), "compareAsOf does not apply to " + key);
        refuse(f.groupBy() != null && !d.groupByOptions().contains(f.groupBy()),
            key + " cannot be grouped by " + f.groupBy());
        refuse(f.clientType() != null && !CLIENT_TYPES.contains(f.clientType()),
            "clientType must be one of " + CLIENT_TYPES);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new ReportParameterException(message);
    }

    private static void refuse(boolean condition, String message) {
        if (condition) throw new ReportParameterException(message);
    }
}
