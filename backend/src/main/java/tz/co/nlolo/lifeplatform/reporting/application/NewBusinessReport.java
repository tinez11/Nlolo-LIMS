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
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.MonthCount;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.NewBusinessRow;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

@Component
public class NewBusinessReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("new-business", "New business",
        "Policies activated each month, offers that expired unpaid, and the take-up rate between them.",
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false);

    private final ReportQueries queries;

    public NewBusinessReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        Map<String, NewBusinessRow> sold = new HashMap<>();
        queries.activations(tenantId, f.from(), f.to(), groupBy, f)
            .forEach(r -> sold.put(r.month() + "|" + r.dimension() + "|" + r.currency(), r));
        Map<String, MonthCount> expired = new HashMap<>();
        queries.notTakenUp(tenantId, f.from(), f.to(), groupBy, f)
            .forEach(r -> expired.put(r.month() + "|" + r.dimension() + "|" + r.currency(), r));

        List<ReportColumn> columns = List.of(col("month", "Month", TEXT), col("dimension", groupBy.label(), TEXT),
            col("currency", "Currency", TEXT), col("policies", "Policies activated", COUNT),
            col("sumAssured", "Sum assured", MONEY), col("annualisedPremium", "Annualised premium", MONEY),
            col("singlePremiums", "Single premiums", MONEY), col("notTakenUp", "Offers not taken up", COUNT),
            col("takeUpRate", "Take-up rate %", PERCENT));

        List<Map<String, String>> rows = new ArrayList<>();
        TreeSet<String> keys = new TreeSet<>(sold.keySet());
        keys.addAll(expired.keySet());
        for (String key : keys) {
            String[] parts = key.split("\\|", -1);
            NewBusinessRow s = sold.get(key);
            long activated = s == null ? 0 : s.policies();
            long lapsedOffers = expired.containsKey(key) ? expired.get(key).count() : 0;
            Map<String, String> row = new LinkedHashMap<>();
            row.put("month", parts[0]);
            row.put("dimension", labels.dimension(groupBy, parts[1]));
            row.put("currency", parts[2]);
            row.put("policies", Figures.count(activated));
            row.put("sumAssured", Figures.money(s == null ? null : s.sumAssured()));
            row.put("annualisedPremium", Figures.money(s == null ? null : s.annualised()));
            row.put("singlePremiums", Figures.money(s == null ? null : s.single()));
            row.put("notTakenUp", Figures.count(lapsedOffers));
            row.put("takeUpRate", Figures.percent(BigDecimal.valueOf(activated), BigDecimal.valueOf(activated + lapsedOffers)));
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("month")).thenComparing(r -> r.get("dimension")));
        Map<String, String> totals = new LinkedHashMap<>(Sections.totals(columns, rows));
        if (!totals.isEmpty()) {
            long activated = Long.parseLong(totals.get("policies"));
            long offers = Long.parseLong(totals.get("notTakenUp"));
            totals.put("takeUpRate", Figures.percent(BigDecimal.valueOf(activated), BigDecimal.valueOf(activated + offers)));
        }
        return List.of(new ReportSection("Activated " + f.from() + " to " + f.to(), columns, rows, totals));
    }
}
