package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;

/**
 * One catalogue entry: everything a caller needs to ask for this report correctly, before asking.
 *
 * @param comparable      the report accepts a second as-of date to compare against
 * @param requiresAccount the report is about ONE chart account and cannot run without one. The console asks
 *                        for it beside the other filters rather than letting the server refuse a submission:
 *                        a field nobody filled in is not a failure to report, it is a question to ask.
 */
public record ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                   DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable,
                                   boolean requiresAccount) {

    /** A report that takes any account, or none -- every report but the general ledger. */
    public ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable) {
        this(key, title, description, category, dateFilterKind, groupByOptions, comparable, false);
    }
}
