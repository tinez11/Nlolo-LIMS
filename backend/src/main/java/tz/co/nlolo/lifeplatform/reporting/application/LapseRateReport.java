package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

@Component
public class LapseRateReport implements Report {

    static final String LAPSED = "policy.PolicyLapsed";
    static final String REINSTATED = "policy.PolicyReinstated";

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("lapse-rate", "Lapse rate",
        "Lapses in the period divided by the average number of policies in force (start plus end, halved), "
            + "gross and net of reinstatements in the same period.",
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false);

    private final ReportQueries queries;

    public LapseRateReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        Map<String, Long> start = policiesByDimension(tenantId, f.from().minusDays(1), groupBy, f);
        Map<String, Long> end = policiesByDimension(tenantId, f.to(), groupBy, f);
        Map<String, Long> lapses = queries.causeCounts(tenantId, LAPSED, f.from(), f.to(), groupBy, f);
        Map<String, Long> reinstated = queries.causeCounts(tenantId, REINSTATED, f.from(), f.to(), groupBy, f);

        List<ReportColumn> columns = List.of(col("dimension", groupBy.label(), TEXT),
            col("inForceStart", "In force at start", COUNT), col("inForceEnd", "In force at end", COUNT),
            col("averageInForce", "Average in force", TEXT), col("lapses", "Lapses", COUNT),
            col("reinstatements", "Reinstatements", COUNT), col("grossLapseRate", "Gross lapse rate %", PERCENT),
            col("netLapseRate", "Net lapse rate %", PERCENT));

        TreeSet<String> keys = new TreeSet<>(start.keySet());
        keys.addAll(end.keySet());
        keys.addAll(lapses.keySet());
        List<Map<String, String>> rows = new ArrayList<>();
        long[] sums = new long[4];
        for (String key : keys) {
            long s = start.getOrDefault(key, 0L);
            long e = end.getOrDefault(key, 0L);
            long l = lapses.getOrDefault(key, 0L);
            long r = reinstated.getOrDefault(key, 0L);
            rows.add(row(labels.dimension(groupBy, key), s, e, l, r));
            sums[0] += s;
            sums[1] += e;
            sums[2] += l;
            sums[3] += r;
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("dimension")));
        Map<String, String> totals = rows.isEmpty() ? Map.of() : row("Total", sums[0], sums[1], sums[2], sums[3]);
        return List.of(new ReportSection("Lapses " + f.from() + " to " + f.to(), columns, rows, totals));
    }

    private Map<String, Long> policiesByDimension(UUID tenantId, LocalDate asOf, GroupBy groupBy, ReportFilter f) {
        Map<String, Long> counts = new HashMap<>();
        queries.inForce(tenantId, asOf, groupBy, f).forEach(r -> counts.merge(r.dimension(), r.policies(), Long::sum));
        return counts;
    }

    private static Map<String, String> row(String label, long start, long end, long lapses, long reinstatements) {
        BigDecimal average = BigDecimal.valueOf(start + end).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dimension", label);
        row.put("inForceStart", Figures.count(start));
        row.put("inForceEnd", Figures.count(end));
        row.put("averageInForce", average.toPlainString());
        row.put("lapses", Figures.count(lapses));
        row.put("reinstatements", Figures.count(reinstatements));
        row.put("grossLapseRate", Figures.percent(BigDecimal.valueOf(lapses), average));
        row.put("netLapseRate", Figures.percent(BigDecimal.valueOf(lapses - reinstatements), average));
        return row;
    }
}
