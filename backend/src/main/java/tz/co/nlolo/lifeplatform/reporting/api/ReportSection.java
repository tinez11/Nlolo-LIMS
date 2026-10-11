package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.List;
import java.util.Map;

/** One table. Every value is a string keyed by column key. totals is empty, never null, when a section has
 * no total row (a sum across currencies, for instance, means nothing). */
public record ReportSection(String title, List<ReportColumn> columns, List<Map<String, String>> rows,
                            Map<String, String> totals) {}
