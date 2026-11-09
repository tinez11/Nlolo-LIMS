package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.util.List;

/** What a report run produces besides its title and summary: sections, headline figures, a chart, a comparison. */
public record ReportBody(List<ReportSection> sections, List<ReportFigure> figures, ReportChart chart,
                         String comparisonLabel) {

    public static ReportBody of(List<ReportSection> sections) {
        return new ReportBody(sections, List.of(), null, null);
    }
}
