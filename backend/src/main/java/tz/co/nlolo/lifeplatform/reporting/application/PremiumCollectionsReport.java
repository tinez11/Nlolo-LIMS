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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/** Channel is MOBILE_MONEY or FIELD_CASH only: PremiumCollected names no payment method, just whether a payer
 * number was present. Billed-versus-collected belongs to the Finance batch. */
@Component
public class PremiumCollectionsReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("premium-collections", "Premium collections",
        "Premiums received each month by channel (mobile money or cash field receipt), and the policies that paid most.",
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false);

    private final ReportQueries queries;

    public PremiumCollectionsReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        List<ReportColumn> byMonth = List.of(col("month", "Month", TEXT), col("dimension", groupBy.label(), TEXT),
            col("channel", "Channel", TEXT), col("currency", "Currency", TEXT), col("receipts", "Receipts", COUNT),
            col("amount", "Amount collected", MONEY));
        List<Map<String, String>> monthRows = queries.collections(tenantId, f.from(), f.to(), groupBy, f).stream().map(r -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("month", r.month());
            row.put("dimension", labels.dimension(groupBy, r.dimension()));
            row.put("channel", r.channel());
            row.put("currency", r.currency());
            row.put("receipts", Figures.count(r.receipts()));
            row.put("amount", Figures.money(r.amount()));
            return row;
        }).toList();

        List<ReportColumn> top = List.of(col("policyNumber", "Policy", TEXT), col("currency", "Currency", TEXT),
            col("receipts", "Receipts", COUNT), col("amount", "Amount collected", MONEY));
        List<Map<String, String>> topRows = queries.topPolicies(tenantId, f.from(), f.to(), f, 10).stream().map(r -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("policyNumber", r.policyNumber());
            row.put("currency", r.currency());
            row.put("receipts", Figures.count(r.receipts()));
            row.put("amount", Figures.money(r.amount()));
            return row;
        }).toList();

        return List.of(
            new ReportSection("Collected " + f.from() + " to " + f.to(), byMonth, monthRows, Sections.totals(byMonth, monthRows)),
            new ReportSection("Top 10 policies by amount collected", top, topRows, Map.of()));
    }
}
