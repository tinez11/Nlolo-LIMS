package tz.co.nlolo.lifeplatform.reporting.api;

import java.util.Set;

/**
 * One column of a report table.
 *
 * @param link what a cell opens (2026-11-11, analytics plan 2, drill-down): POLICY, CLAIM, CLIENT or AGENT; null for
 *             none. The id is the row's hidden {@code "_" + key + "Id"} value, or -- for POLICY only -- the cell's own
 *             text, a policy number. Hidden {@code _} keys are never printed: exports go by column.
 */
public record ReportColumn(String key, String label, ColumnType type, String link) {

    public static final Set<String> LINKS = Set.of("POLICY", "CLAIM", "CLIENT", "AGENT");

    public ReportColumn {
        if (link != null && !LINKS.contains(link)) {
            throw new IllegalArgumentException("Unknown link " + link + "; one of " + LINKS);
        }
    }

    public ReportColumn(String key, String label, ColumnType type) {
        this(key, label, type, null);
    }
}
