package tz.co.nlolo.lifeplatform.reporting.application;

import tz.co.nlolo.lifeplatform.reporting.api.ColumnType;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
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
