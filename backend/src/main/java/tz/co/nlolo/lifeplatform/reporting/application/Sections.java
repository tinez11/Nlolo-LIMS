package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class Sections {
    private Sections() {}

    static ReportColumn col(String key, String label, ColumnType type) {
        return new ReportColumn(key, label, type);
    }

    /**
     * The first section's total row as headline figures: each counted, money or percentage total, the first three on
     * the band and the next four as tiles. Nothing is added up here that the table did not already total; no total row
     * (an empty report, or one in two currencies) means no figures.
     */
    static List<ReportFigure> figuresFromTotals(List<ReportSection> sections) {
        ReportSection first = sections.stream().filter(s -> !s.totals().isEmpty()).findFirst().orElse(null);
        if (first == null) return List.of();
        List<ReportFigure> figures = new ArrayList<>();
        for (int i = 1; i < first.columns().size() && figures.size() < 7; i++) {
            ReportColumn c = first.columns().get(i);
            String value = first.totals().get(c.key());
            if (value == null || value.isBlank()) continue;
            String unit = switch (c.type()) {
                case COUNT -> "COUNT";
                case MONEY -> "MONEY";
                case PERCENT -> "PERCENT";
                default -> null;
            };
            if (unit == null) continue;
            figures.add(new ReportFigure(c.key(), c.label(), value, null, null, null, null, figures.size() < 3, unit));
        }
        return figures;
    }

    /**
     * A headline figure read from a total row, against the same total of the run it is compared with (2026-11-09,
     * batch 4): both totals are the reports' own, worked out the same way, so the change is like for like. Null when
     * the total is blank -- no rows, or two currencies.
     */
    static ReportFigure compared(String key, String label, Map<String, String> now, Map<String, String> before,
                                 String unit, String goodWhen, boolean lead) {
        String value = now.get(key);
        if (value == null || value.isBlank()) return null;
        String prior = before == null ? null : before.get(key);
        if (prior != null && prior.isBlank()) prior = null;
        String change = null;
        String changePercent = null;
        if (prior != null) {
            BigDecimal a = new BigDecimal(value);
            BigDecimal b = new BigDecimal(prior);
            change = "COUNT".equals(unit) ? a.subtract(b).toBigInteger().toString() : a.subtract(b).toPlainString();
            if (!"PERCENT".equals(unit)) changePercent = ClaimsReporting.percent(a, b);
        }
        return new ReportFigure(key, label, value, prior, change, changePercent, goodWhen, lead, unit);
    }

    /** The figures that are not null, in order. */
    static List<ReportFigure> present(ReportFigure... figures) {
        List<ReportFigure> out = new ArrayList<>();
        for (ReportFigure f : figures) if (f != null) out.add(f);
        return out;
    }

    /** "compared with 2026-09-01 to 2026-09-30", or null. */
    static String comparedWith(java.time.LocalDate[] span) {
        return span == null ? null : "compared with " + span[0] + " to " + span[1];
    }

    /**
     * Sums every COUNT and MONEY column; the first column reads "Total"; everything else is blank. Empty --
     * no total row -- when the rows carry more than one currency, because adding shillings to dollars is a
     * number that means nothing.
     */
    static Map<String, String> totals(List<ReportColumn> columns, List<Map<String, String>> rows) {
        if (rows.isEmpty()) return Map.of();
        long currencies = rows.stream().map(r -> r.get("currency")).filter(Objects::nonNull).distinct().count();
        if (currencies > 1) return Map.of();
        Map<String, String> totals = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            ReportColumn c = columns.get(i);
            if (i == 0) {
                totals.put(c.key(), "Total");
            } else if (c.type() == ColumnType.COUNT) {
                totals.put(c.key(), Figures.count(rows.stream().mapToLong(r -> Long.parseLong(r.getOrDefault(c.key(), "0"))).sum()));
            } else if (c.type() == ColumnType.MONEY) {
                totals.put(c.key(), Figures.money(rows.stream().map(r -> new BigDecimal(r.getOrDefault(c.key(), "0")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add)));
            } else if ("currency".equals(c.key())) {
                totals.put(c.key(), rows.get(0).get("currency"));
            } else {
                totals.put(c.key(), "");
            }
        }
        return totals;
    }
}
