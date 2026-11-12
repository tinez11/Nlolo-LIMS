package tz.co.nlolo.lifeplatform.reporting.infrastructure;

import tz.co.nlolo.lifeplatform.organisation.api.BrandedDocument;
import tz.co.nlolo.lifeplatform.organisation.api.BrandedPdf;
import tz.co.nlolo.lifeplatform.organisation.api.Letterhead;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A report as a PDF in the company's letterhead (2026-11-09, report formatting -- before, a bare table cut off with "~"
 * and machine time): the title, the period and what it is compared with, who prepared it and when; the headline
 * figures; the notices; the chart; then each section as a table whose figures are written in their unit and
 * right-aligned, whose text wraps, whose subtotals are bold and totals ruled, and whose heading repeats on every page.
 * The footer carries the company's confidentiality line and "Page X of Y". A wide report turns the page.
 */
final class ReportPdf {

    private ReportPdf() {}

    /** More columns than this and the page turns. */
    private static final int PORTRAIT_COLUMNS = 6;

    static byte[] render(ReportResult result, Letterhead letterhead, String preparedBy) {
        return BrandedPdf.render(document(result, preparedBy), letterhead);
    }

    static BrandedDocument document(ReportResult result, String preparedBy) {
        List<String> subtitle = new ArrayList<>();
        if (present(result.filterSummary())) subtitle.add(result.filterSummary());
        if (present(result.comparisonLabel())) subtitle.add(result.comparisonLabel());
        subtitle.add("Prepared" + (present(preparedBy) ? " by " + preparedBy : "") + " on "
            + ReportFormat.stamp(result.generatedAt()));
        // How fresh the figures are (2026-11-11, analytics plan 2), on paper as on screen.
        if (result.dataUpTo() != null) subtitle.add("Last change taken in " + ReportFormat.stamp(result.dataUpTo()));

        List<BrandedDocument.Figure> figures = result.figures().stream()
            .map(f -> new BrandedDocument.Figure(f.label(), ReportFormat.figure(f), ReportFormat.caption(f))).toList();

        List<BrandedDocument.Table> tables = new ArrayList<>();
        int widest = 0;
        for (ReportSection section : result.sections()) {
            List<ReportColumn> columns = section.columns().stream().filter(c -> !c.key().startsWith("_")).toList();
            widest = Math.max(widest, columns.size());
            List<BrandedDocument.Column> cols = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                ReportColumn c = columns.get(i);
                boolean number = ReportFormat.numeric(c.type());
                cols.add(new BrandedDocument.Column(c.label(), number, number ? 1.2f : i == 0 ? 2.8f : 1.8f));
            }
            List<BrandedDocument.Row> rows = new ArrayList<>();
            for (Map<String, String> row : section.rows()) rows.add(row(columns, row));
            if (!section.totals().isEmpty()) {
                List<String> cells = new ArrayList<>();
                for (ReportColumn c : columns) cells.add(ReportFormat.cell(c.type(), section.totals().get(c.key())));
                rows.add(new BrandedDocument.Row(cells, BrandedDocument.Style.TOTAL, 0));
            }
            tables.add(new BrandedDocument.Table(section.title(), cols, rows));
        }
        return new BrandedDocument(result.title(), String.join(" · ", subtitle), List.of(), figures, result.notices(),
            chart(result.chart()), tables, List.of(), List.of(), widest > PORTRAIT_COLUMNS, true, null);
    }

    private static BrandedDocument.Row row(List<ReportColumn> columns, Map<String, String> row) {
        List<String> cells = new ArrayList<>();
        for (ReportColumn c : columns) cells.add(ReportFormat.cell(c.type(), row.get(c.key())));
        BrandedDocument.Style style = switch (row.getOrDefault("_kind", "LINE")) {
            case "SUBTOTAL" -> BrandedDocument.Style.SUBTOTAL;
            case "TOTAL" -> BrandedDocument.Style.TOTAL;
            default -> BrandedDocument.Style.LINE;
        };
        int level;
        try {
            level = Integer.parseInt(row.getOrDefault("_level", "0"));
        } catch (NumberFormatException e) {
            level = 0;
        }
        return new BrandedDocument.Row(cells, style, level);
    }

    /** The screen's chart, drawn under the figures: a bridge's bars, or a trend's lines over its periods. */
    static BrandedDocument.Chart chart(ReportChart c) {
        if (c == null) return null;
        if ("TREND".equals(c.kind())) {
            if (c.series().isEmpty()) return null;
            List<String> periods = c.series().get(0).points().stream().map(ReportChart.Point::period).toList();
            List<BrandedDocument.Series> series = c.series().stream().map(s -> new BrandedDocument.Series(s.label(),
                s.dashed(), s.points().stream().map(p -> number(p.value())).toList())).toList();
            return new BrandedDocument.Chart("TREND", c.description(), List.of(), periods, series, c.unit());
        }
        if (c.bars().isEmpty()) return null;
        List<BrandedDocument.Bar> bars = c.bars().stream().map(b -> new BrandedDocument.Bar(b.label(),
            number(b.value()) == null ? 0 : number(b.value()), b.kind())).toList();
        return new BrandedDocument.Chart("BRIDGE", c.description(), bars, List.of(), List.of(), c.unit());
    }

    private static Double number(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean present(String s) {
        return s != null && !s.isBlank();
    }
}
