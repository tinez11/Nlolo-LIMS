package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.util.List;

/** What a report answers: sections, headline figures, a chart, what it compared with and what to know first. */
public record ReportBody(List<ReportSection> sections, List<ReportFigure> figures, ReportChart chart,
                         String comparisonLabel, List<String> notices) {

    public ReportBody {
        notices = notices == null ? List.of() : List.copyOf(notices);
    }

    public ReportBody(List<ReportSection> sections, List<ReportFigure> figures, ReportChart chart, String comparisonLabel) {
        this(sections, figures, chart, comparisonLabel, List.of());
    }

    public static ReportBody of(List<ReportSection> sections) {
        return new ReportBody(sections, List.of(), null, null);
    }
}
