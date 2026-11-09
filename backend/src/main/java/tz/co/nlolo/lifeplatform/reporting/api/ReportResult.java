package tz.co.nlolo.lifeplatform.reporting.api;

import java.time.Instant;
import java.util.List;

/**
 * A report run: its sections, and (2026-11-09, the redesign) its headline figures, its one chart and what it was
 * compared with. A report that has no figures or chart yet answers an empty list and null.
 *
 * @param notices what a reader must know before trusting the figures -- "Placeholder risk charges, not TIRA's" --
 *                shown above them, never in a footnote (2026-11-09, the solvency reports)
 */
public record ReportResult(String key, String title, String filterSummary, Instant generatedAt,
                           List<ReportSection> sections, List<ReportFigure> figures, ReportChart chart,
                           String comparisonLabel, List<String> notices) {

    public ReportResult {
        figures = figures == null ? List.of() : List.copyOf(figures);
        notices = notices == null ? List.of() : List.copyOf(notices);
    }

    public ReportResult(String key, String title, String filterSummary, Instant generatedAt,
                        List<ReportSection> sections, List<ReportFigure> figures, ReportChart chart,
                        String comparisonLabel) {
        this(key, title, filterSummary, generatedAt, sections, figures, chart, comparisonLabel, List.of());
    }

    public ReportResult(String key, String title, String filterSummary, Instant generatedAt,
                        List<ReportSection> sections) {
        this(key, title, filterSummary, generatedAt, sections, List.of(), null, null, List.of());
    }
}
