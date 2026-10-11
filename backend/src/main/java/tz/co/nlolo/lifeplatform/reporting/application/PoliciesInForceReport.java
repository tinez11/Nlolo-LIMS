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
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.InForceRow;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

@Component
public class PoliciesInForceReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("policies-in-force", "Policies in force",
        "Policies on cover on a date: count, sum assured and premium. Suspended and paid-up policies are in force; "
            + "a paid-up policy contributes no premium.",
        ReportCategory.MANAGEMENT, DateFilterKind.AS_OF, List.of(GroupBy.values()), true);

    private final ReportQueries queries;

    public PoliciesInForceReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        Map<String, InForceRow> now = index(queries.inForce(tenantId, f.asOf(), groupBy, f));
        Map<String, InForceRow> then = f.compareAsOf() == null ? Map.of()
            : index(queries.inForce(tenantId, f.compareAsOf(), groupBy, f));

        List<ReportColumn> columns = new ArrayList<>(List.of(col("dimension", groupBy.label(), TEXT),
            col("currency", "Currency", TEXT), col("policies", "Policies", COUNT), col("sumAssured", "Sum assured", MONEY),
            col("annualisedPremium", "Annualised premium", MONEY), col("singlePremiums", "Single premiums", MONEY)));
        if (f.compareAsOf() != null) {
            columns.add(col("comparePolicies", "Policies on " + f.compareAsOf(), COUNT));
            columns.add(col("policyChange", "Change", COUNT));
        }

        Set<String> keys = new LinkedHashSet<>(now.keySet());
        keys.addAll(then.keySet());
        List<Map<String, String>> rows = new ArrayList<>();
        for (String key : keys) {
            InForceRow n = now.get(key);
            InForceRow c = then.get(key);
            InForceRow any = n != null ? n : c;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("dimension", labels.dimension(groupBy, any.dimension()));
            row.put("currency", any.currency());
            row.put("policies", Figures.count(n == null ? 0 : n.policies()));
            row.put("sumAssured", Figures.money(n == null ? null : n.sumAssured()));
            row.put("annualisedPremium", Figures.money(n == null ? null : n.annualised()));
            row.put("singlePremiums", Figures.money(n == null ? null : n.single()));
            if (f.compareAsOf() != null) {
                long before = c == null ? 0 : c.policies();
                row.put("comparePolicies", Figures.count(before));
                row.put("policyChange", Figures.count((n == null ? 0 : n.policies()) - before));
            }
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("dimension")).thenComparing(r -> r.get("currency")));
        return List.of(new ReportSection("Policies in force on " + f.asOf(), columns, rows, Sections.totals(columns, rows)));
    }

    private static Map<String, InForceRow> index(List<InForceRow> rows) {
        Map<String, InForceRow> byKey = new LinkedHashMap<>();
        rows.forEach(r -> byKey.put(r.dimension() + "|" + r.currency(), r));
        return byKey;
    }
}
