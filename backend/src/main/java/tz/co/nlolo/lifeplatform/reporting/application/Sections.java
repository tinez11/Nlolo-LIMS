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
