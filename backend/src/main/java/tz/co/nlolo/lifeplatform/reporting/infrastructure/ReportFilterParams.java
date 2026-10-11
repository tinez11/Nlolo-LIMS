package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Query string to ReportFilter. A value that does not parse is a 400 naming the parameter and the shape it wants. */
final class ReportFilterParams {
    private ReportFilterParams() {}

    static ReportFilter parse(Map<String, String> q) {
        return new ReportFilter(
            value(q, "asOf", LocalDate::parse, "a date, YYYY-MM-DD"),
            value(q, "compareAsOf", LocalDate::parse, "a date, YYYY-MM-DD"),
            value(q, "from", LocalDate::parse, "a date, YYYY-MM-DD"),
            value(q, "to", LocalDate::parse, "a date, YYYY-MM-DD"),
            value(q, "cohortFrom", YearMonth::parse, "a month, YYYY-MM"),
            value(q, "cohortTo", YearMonth::parse, "a month, YYYY-MM"),
            value(q, "productId", UUID::fromString, "a uuid"),
            blankToNull(q.get("productCategory")),
            value(q, "agentId", UUID::fromString, "a uuid"),
            blankToNull(q.get("salesChannel")),
            blankToNull(q.get("branchCode")),
            blankToNull(q.get("clientType")),
            value(q, "groupBy", GroupBy::valueOf, "one of PRODUCT, PRODUCT_CATEGORY, AGENT, CHANNEL, BRANCH"));
    }

    private static <T> T value(Map<String, String> q, String name, Function<String, T> parser, String shape) {
        String raw = blankToNull(q.get(name));
        if (raw == null) return null;
        try {
            return parser.apply(raw);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            throw new ReportParameterException(name + " must be " + shape + "; got '" + raw + "'");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
