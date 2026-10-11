package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;

/** One catalogue entry. comparable: the report accepts a second as-of date to compare against. */
public record ReportDefinitionView(String key, String title, String description, ReportCategory category,
                                   DateFilterKind dateFilterKind, List<GroupBy> groupByOptions, boolean comparable) {}
