package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.ClaimFact;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.ClaimsReporting.Span;

/**
 * C4, what was approved against what was paid (2026-11-09): of the claims paid in the period, the approved amount, what
 * the settlement asked for after netting (an outstanding loan, unpaid premium) and what was actually paid -- and the
 * approved claims still unpaid at the end, by how long they have waited.
 */
@Component
public class ApprovedVsPaidReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("claims-approved-vs-paid", "Approved vs paid",
        "Claims paid in the period: approved, netted and paid; and approved claims still unpaid at its end, by age.",
        ReportCategory.CLAIMS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods();

    private static final int[][] AGES = { { 0, 30 }, { 31, 60 }, { 61, 90 }, { 91, Integer.MAX_VALUE } };

    private final ReportQueries queries;

    public ApprovedVsPaidReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        return body(tenantId, f, labels).sections();
    }

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        Span now = ClaimsReporting.current(f);
        Span before = ClaimsReporting.prior(f);
        List<ClaimFact> claims = queries.claimFacts(tenantId, now.to(), f);

        Sums paid = sums(claims, now);
        Sums paidWas = before == null ? null : sums(claims, before);

        List<ReportColumn> columns = new ArrayList<>(List.of(new ReportColumn("line", "", TEXT),
            new ReportColumn("amount", now.label(), MONEY)));
        if (before != null) {
            columns.add(new ReportColumn("priorAmount", before.label(), MONEY));
            columns.add(new ReportColumn("change", "Change", MONEY));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(line("Approved", paid.approved(), paidWas == null ? null : paidWas.approved()));
        rows.add(line("less netted at settlement (loans, unpaid premium)", paid.netted(),
            paidWas == null ? null : paidWas.netted()));
        rows.add(line("Asked to pay", paid.payable(), paidWas == null ? null : paidWas.payable()));
        rows.add(line("Paid", paid.paid(), paidWas == null ? null : paidWas.paid()));

        // Approved and not yet paid at the end, aged from approval.
        List<ReportColumn> ageColumns = List.of(new ReportColumn("line", "Waiting since approval", TEXT),
            new ReportColumn("count", "Claims", COUNT), new ReportColumn("amount", "Approved amount", MONEY));
        List<Map<String, String>> ageRows = new ArrayList<>();
        BigDecimal unpaid = BigDecimal.ZERO;
        long unpaidCount = 0;
        for (int[] band : AGES) {
            long n = 0;
            BigDecimal sum = BigDecimal.ZERO;
            for (ClaimFact c : claims) {
                if (!unpaidOn(c, now)) continue;
                long days = ChronoUnit.DAYS.between(c.approvedOn(), now.to());
                if (days >= band[0] && days <= band[1]) {
                    n++;
                    sum = sum.add(c.approvedAmount() == null ? BigDecimal.ZERO : c.approvedAmount());
                }
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("line", band[1] == Integer.MAX_VALUE ? "Over 90 days" : band[0] + " to " + band[1] + " days");
            row.put("count", Figures.count(n));
            row.put("amount", Figures.money(sum));
            ageRows.add(row);
            unpaid = unpaid.add(sum);
            unpaidCount += n;
        }
        Map<String, String> ageTotals = new LinkedHashMap<>();
        ageTotals.put("line", "All unpaid at " + now.to());
        ageTotals.put("count", Figures.count(unpaidCount));
        ageTotals.put("amount", Figures.money(unpaid));

        BigDecimal unpaidWas = before == null ? null : claims.stream().filter(c -> unpaidOn(c, before))
            .map(c -> c.approvedAmount() == null ? BigDecimal.ZERO : c.approvedAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<ReportFigure> figures = List.of(
            ClaimsReporting.money("paid", "Paid", paid.paid(), paidWas == null ? null : paidWas.paid(), null, true),
            ClaimsReporting.money("netted", "Netted at settlement", paid.netted(), paidWas == null ? null : paidWas.netted(),
                null, true),
            ClaimsReporting.money("unpaid", "Approved, still unpaid", unpaid, unpaidWas, "DOWN", true));
        return new ReportBody(List.of(
            new ReportSection("Claims paid in the period", columns, rows, Map.of()),
            new ReportSection("Approved claims still unpaid, by age", ageColumns, ageRows, ageTotals)),
            figures, null, StatementReports.comparisonLabel(f.comparisonMonths()));
    }

    private record Sums(BigDecimal approved, BigDecimal payable, BigDecimal paid) {
        BigDecimal netted() {
            return approved.subtract(payable);
        }
    }

    /** The claims PAID in a span: their approved, payable and paid amounts (payable defaults to approved). */
    private static Sums sums(List<ClaimFact> claims, Span span) {
        BigDecimal approved = BigDecimal.ZERO;
        BigDecimal payable = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        for (ClaimFact c : claims) {
            if (!span.holds(c.settledOn())) continue;
            BigDecimal a = c.approvedAmount() == null ? BigDecimal.ZERO : c.approvedAmount();
            approved = approved.add(a);
            payable = payable.add(c.payableAmount() == null ? a : c.payableAmount());
            paid = paid.add(c.paidAmount() == null ? BigDecimal.ZERO : c.paidAmount());
        }
        return new Sums(approved, payable, paid);
    }

    /** Approved by the span's end and not paid by then. */
    private static boolean unpaidOn(ClaimFact c, Span span) {
        return c.approvedOn() != null && !c.approvedOn().isAfter(span.to())
            && (c.settledOn() == null || c.settledOn().isAfter(span.to()));
    }

    private static Map<String, String> line(String label, BigDecimal now, BigDecimal before) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("line", label);
        row.put("amount", Figures.money(now));
        if (before != null) {
            row.put("priorAmount", Figures.money(before));
            row.put("change", Figures.money(now.subtract(before)));
        }
        return row;
    }
}
