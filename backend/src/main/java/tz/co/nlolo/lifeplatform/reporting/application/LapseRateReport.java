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
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false).comparingPeriods();

    private final ReportQueries queries;

    public LapseRateReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    /**
     * The period's rate against the period it is compared with, and (2026-11-09, batch 4) how far into their lives the
     * policies lapsed: in their first year, their second, or later. A lapse in year one is a selling problem; later, a
     * service one.
     */
    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        List<ReportSection> sections = new ArrayList<>(run(tenantId, f, labels));
        java.time.LocalDate[] span = f.comparisonSpan();
        Map<String, String> now = sections.get(0).totals();
        Map<String, String> before = span == null ? null
            : run(tenantId, f.withPeriod(span[0], span[1]), labels).get(0).totals();
        sections.add(byPolicyYear(tenantId, f));
        return new ReportBody(sections, Sections.present(
            Sections.compared("grossLapseRate", "Gross lapse rate", now, before, "PERCENT", "DOWN", true),
            Sections.compared("lapses", "Lapses", now, before, "COUNT", "DOWN", true),
            Sections.compared("netLapseRate", "Net of reinstatements", now, before, "PERCENT", "DOWN", true),
            Sections.compared("reinstatements", "Reinstatements", now, before, "COUNT", "UP", false),
            Sections.compared("inForceEnd", "In force at the end", now, before, "COUNT", "UP", false)),
            null, Sections.comparedWith(span));
    }

    /** Lapses by the policy year they fell in: year 1 is the first twelve months from activation. */
    private ReportSection byPolicyYear(UUID tenantId, ReportFilter f) {
        long[] years = new long[3];
        for (ReportQueries.LapseAge l : queries.lapseAges(tenantId, f.from(), f.to(), GroupBy.PRODUCT, f)) {
            years[policyYear(l.activatedOn(), l.lapsedOn()) - 1]++;
        }
        long all = years[0] + years[1] + years[2];
        List<ReportColumn> columns = List.of(col("year", "Policy year", TEXT), col("lapses", "Lapses", COUNT),
            col("share", "Share of lapses %", PERCENT));
        String[] names = { "Year 1", "Year 2", "Year 3 or later" };
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("year", names[i]);
            row.put("lapses", Figures.count(years[i]));
            row.put("share", Figures.percent(BigDecimal.valueOf(years[i]), BigDecimal.valueOf(all)));
            rows.add(row);
        }
        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("year", "Total");
        totals.put("lapses", Figures.count(all));
        totals.put("share", all == 0 ? "" : "100.00");
        return new ReportSection("Lapses by policy year", columns, rows, totals);
    }

    /** 1, 2 or 3 (meaning 3 or later). A lapse with no activation date is counted in year 1. */
    static int policyYear(LocalDate activatedOn, LocalDate lapsedOn) {
        if (activatedOn == null || lapsedOn == null) return 1;
        long years = java.time.temporal.ChronoUnit.YEARS.between(activatedOn, lapsedOn);
        return (int) Math.min(3, Math.max(0, years) + 1);
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
