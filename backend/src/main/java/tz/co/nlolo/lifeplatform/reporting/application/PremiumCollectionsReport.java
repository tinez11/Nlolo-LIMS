package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.ReceivablesAging;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Premiums received in a period (2026-11-09, upgraded in the reports redesign batch 4): against the period before or
 * the same period last year, by payment method, month by month, the policies that paid most -- and what is still owed
 * at the period's end, by how overdue it is.
 *
 * <p>The arrears are billing's own ageing of the whole book on the period's last day: billing knows invoices, which the
 * reporting projection does not, so the product and client filters do not narrow it, and its title says so.
 */
@Component
public class PremiumCollectionsReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("premium-collections", "Premium collections",
        "Premiums received each month, by payment method, against the period before; the policies that paid most; and"
            + " what is still owed at the period's end, by how overdue it is.",
        ReportCategory.MANAGEMENT, DateFilterKind.PERIOD, List.of(GroupBy.values()), false).comparingPeriods();

    /** Every way a premium is paid, in words (2026-11-11). FIELD_CASH is the old guess, kept for rows projected before. */
    static final Map<String, String> METHODS = Map.of("MOBILE_MONEY", "Mobile money", "LIPA_NUMBER", "Lipa number",
        "TANQR", "TANQR", "CASH", "Cash", "BANK_DEPOSIT", "Bank deposit", "CHEQUE", "Cheque", "AGENT_CASH",
        "Cash paid to an agent", "FIELD_CASH", "Cash (older records)");

    static final Map<String, String> BANDS = Map.of("NOT_DUE", "Not yet due", "D1_30", "1 to 30 days overdue",
        "D31_60", "31 to 60 days overdue", "D61_90", "61 to 90 days overdue", "OVER_90", "More than 90 days overdue");

    private final ReportQueries queries;
    private final BillingApi billing;

    public PremiumCollectionsReport(ReportQueries queries, BillingApi billing) {
        this.queries = queries;
        this.billing = billing;
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
        Map<String, String> before = span == null ? null : collected(tenantId, f.withPeriod(span[0], span[1]), labels);
        return new ReportBody(sections, Sections.present(
            Sections.compared("amount", "Premiums collected", now, before, "MONEY", "UP", true),
            Sections.compared("receipts", "Receipts", now, before, "COUNT", "UP", true)),
            trend(sections.get(0)), Sections.comparedWith(span));
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        GroupBy groupBy = f.groupByOr(GroupBy.PRODUCT);
        List<ReportQueries.CollectionRow> collections = queries.collections(tenantId, f.from(), f.to(), groupBy, f);
        List<ReportColumn> byMonth = List.of(col("month", "Month", TEXT), col("dimension", groupBy.label(), TEXT),
            col("channel", "Payment method", TEXT), col("currency", "Currency", TEXT), col("receipts", "Receipts", COUNT),
            col("amount", "Amount collected", MONEY));
        List<Map<String, String>> monthRows = collections.stream().map(r -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("month", r.month());
            row.put("dimension", labels.dimension(groupBy, r.dimension()));
            row.put("channel", METHODS.getOrDefault(r.channel(), r.channel()));
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
            byMethod(collections),
            new ReportSection("Top 10 policies by amount collected", top, topRows, Map.of()),
            arrears(f.to()));
    }

    /** The compared period's total row, worked out exactly as the period's own. */
    private Map<String, String> collected(UUID tenantId, ReportFilter f, Labels labels) {
        return run(tenantId, f, labels).get(0).totals();
    }

    /** Each payment method's receipts and amount, per currency. */
    private static ReportSection byMethod(List<ReportQueries.CollectionRow> collections) {
        Map<String, long[]> receipts = new TreeMap<>();
        Map<String, BigDecimal> amounts = new TreeMap<>();
        for (ReportQueries.CollectionRow r : collections) {
            String key = METHODS.getOrDefault(r.channel(), r.channel()) + "|" + r.currency();
            receipts.computeIfAbsent(key, k -> new long[1])[0] += r.receipts();
            amounts.merge(key, r.amount(), BigDecimal::add);
        }
        List<ReportColumn> columns = List.of(col("method", "Payment method", TEXT), col("currency", "Currency", TEXT),
            col("receipts", "Receipts", COUNT), col("amount", "Amount collected", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        receipts.forEach((key, n) -> {
            String[] parts = key.split("\\|", -1);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("method", parts[0]);
            row.put("currency", parts[1]);
            row.put("receipts", Figures.count(n[0]));
            row.put("amount", Figures.money(amounts.get(key)));
            rows.add(row);
        });
        return new ReportSection("By payment method", columns, rows, Sections.totals(columns, rows));
    }

    /** Billing's ageing of what is owed on the period's last day, every band shown even when empty. */
    private ReportSection arrears(LocalDate asOf) {
        List<ReportColumn> columns = List.of(col("band", "How overdue", TEXT), col("currency", "Currency", TEXT),
            col("cases", "Invoices", COUNT), col("outstanding", "Outstanding", MONEY));
        List<Map<String, String>> rows = new ArrayList<>();
        for (ReceivablesAging aging : billing.receivablesAging(asOf)) {
            for (ReceivablesAging.Bucket b : aging.buckets()) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("band", BANDS.getOrDefault(b.name(), b.name()));
                row.put("currency", aging.currency());
                row.put("cases", Figures.count(b.cases()));
                row.put("outstanding", Figures.money(b.outstanding()));
                rows.add(row);
            }
        }
        return new ReportSection("Owed on " + asOf + ", by how overdue (the whole book)", columns, rows,
            Sections.totals(columns, rows));
    }

    /** Collected per month, one line: the shape of the period. Only in one currency -- a line of two means nothing. */
    private static ReportChart trend(ReportSection collected) {
        if (collected.rows().stream().map(r -> r.get("currency")).distinct().count() != 1) return null;
        Map<String, BigDecimal> byMonth = new TreeMap<>();
        collected.rows().forEach(r -> byMonth.merge(r.get("month"), new BigDecimal(r.get("amount")), BigDecimal::add));
        if (byMonth.size() < 2) return null;
        List<ReportChart.Point> points = byMonth.entrySet().stream()
            .map(e -> new ReportChart.Point(e.getKey(), Figures.money(e.getValue()))).toList();
        return new ReportChart("TREND", "Premiums collected, month by month", List.of(),
            List.of(new ReportChart.Series("Collected", false, points)), "MONEY");
    }
}
