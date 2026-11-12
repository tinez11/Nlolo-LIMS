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

/**
 * The business that did not stay (2026-11-09, the reports redesign batch 4): policies cancelled in their free-look
 * period, and offers that closed without the first premium, by product, agent, channel or branch, against the period
 * before. Both are a sale that was made and not kept, so a high rate for one agent or product is worth a conversation.
 *
 * <ul>
 *   <li>Free-look rate = cancelled in free-look ÷ policies activated in the period.</li>
 *   <li>Not-taken-up rate = offers not taken up ÷ (activated + not taken up): of the offers that closed either way,
 *   the share that closed unpaid -- the complement of new business's take-up rate.</li>
 * </ul>
 */
@Component
public class FreeLookReport implements Report {

    static final String FREE_LOOK = "policy.PolicyCancelledFreeLook";

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("free-look-and-not-taken-up",
        "Free-look and not taken up",
        "Policies cancelled in the free-look period and offers that closed unpaid, by product, agent, channel or branch,"
            + " against the period before.",
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false).comparingPeriods().from("REPORTING_COPY");

    private final ReportQueries queries;

    public FreeLookReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        List<ReportSection> sections = run(tenantId, f, labels);
        LocalDate[] span = f.comparisonSpan();
        Map<String, String> now = sections.get(0).totals();
        Map<String, String> before = span == null ? null
            : run(tenantId, f.withPeriod(span[0], span[1]), labels).get(0).totals();
        return new ReportBody(sections, Sections.present(
            Sections.compared("freeLook", "Cancelled in free-look", now, before, "COUNT", "DOWN", true),
            Sections.compared("notTakenUp", "Offers not taken up", now, before, "COUNT", "DOWN", true),
            Sections.compared("activated", "Policies activated", now, before, "COUNT", "UP", true),
            Sections.compared("freeLookRate", "Free-look rate", now, before, "PERCENT", "DOWN", false),
            Sections.compared("notTakenUpRate", "Not-taken-up rate", now, before, "PERCENT", "DOWN", false)),
            null, Sections.comparedWith(span));
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        Map<String, Long> activated = new HashMap<>();
        queries.activations(tenantId, f.from(), f.to(), groupBy, f)
            .forEach(r -> activated.merge(r.dimension(), r.policies(), Long::sum));
        Map<String, Long> notTakenUp = new HashMap<>();
        queries.notTakenUp(tenantId, f.from(), f.to(), groupBy, f)
            .forEach(r -> notTakenUp.merge(r.dimension(), r.count(), Long::sum));
        Map<String, Long> freeLook = queries.causeCounts(tenantId, FREE_LOOK, f.from(), f.to(), groupBy, f);

        List<ReportColumn> columns = List.of(col("dimension", groupBy.label(), TEXT),
            col("activated", "Policies activated", COUNT), col("freeLook", "Cancelled in free-look", COUNT),
            col("freeLookRate", "Free-look rate %", PERCENT), col("notTakenUp", "Offers not taken up", COUNT),
            col("notTakenUpRate", "Not-taken-up rate %", PERCENT));
        TreeSet<String> keys = new TreeSet<>(activated.keySet());
        keys.addAll(notTakenUp.keySet());
        keys.addAll(freeLook.keySet());
        List<Map<String, String>> rows = new ArrayList<>();
        long[] sums = new long[3];
        for (String key : keys) {
            long a = activated.getOrDefault(key, 0L);
            long c = freeLook.getOrDefault(key, 0L);
            long n = notTakenUp.getOrDefault(key, 0L);
            rows.add(row(labels.dimension(groupBy, key), a, c, n));
            sums[0] += a;
            sums[1] += c;
            sums[2] += n;
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("dimension")));
        Map<String, String> totals = rows.isEmpty() ? Map.of() : row("Total", sums[0], sums[1], sums[2]);
        return List.of(new ReportSection("Free-look and not taken up, " + f.from() + " to " + f.to(), columns, rows, totals));
    }

    static Map<String, String> row(String label, long activated, long freeLook, long notTakenUp) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("dimension", label);
        row.put("activated", Figures.count(activated));
        row.put("freeLook", Figures.count(freeLook));
        row.put("freeLookRate", Figures.percent(BigDecimal.valueOf(freeLook), BigDecimal.valueOf(activated)));
        row.put("notTakenUp", Figures.count(notTakenUp));
        row.put("notTakenUpRate", Figures.percent(BigDecimal.valueOf(notTakenUp), BigDecimal.valueOf(activated + notTakenUp)));
        return row;
    }
}
