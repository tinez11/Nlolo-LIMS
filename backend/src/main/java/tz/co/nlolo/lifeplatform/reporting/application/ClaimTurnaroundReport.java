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
import java.math.RoundingMode;
import java.time.LocalDate;
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
 * C5, how long claims take against the period before (2026-11-09): days from registration to a decision, from
 * approval to payment and from registration to payment, and the claims paid later than the target
 * ({@link ClaimsReporting#TARGET_DAYS} days from registration).
 */
@Component
public class ClaimTurnaroundReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("claims-turnaround", "Claims turnaround",
        "Average days to decide and to pay the claims settled in the period, against the period before, and the claims"
            + " paid later than " + ClaimsReporting.TARGET_DAYS + " days from registration.",
        ReportCategory.CLAIMS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods().from("REPORTING_COPY");

    private final ReportQueries queries;

    public ClaimTurnaroundReport(ReportQueries queries) {
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

        Times t = times(claims, now);
        Times w = before == null ? null : times(claims, before);

        List<ReportFigure> figures = List.of(
            ClaimsReporting.ratio("toDecide", "Days to a decision", t.toDecide(), w == null ? null : w.toDecide(), "DOWN", "DAYS", true),
            ClaimsReporting.ratio("toPay", "Days from approval to payment", t.approvalToPay(), w == null ? null : w.approvalToPay(),
                "DOWN", "DAYS", true),
            ClaimsReporting.ratio("onTime", "Paid within " + ClaimsReporting.TARGET_DAYS + " days", t.onTimePercent(),
                w == null ? null : w.onTimePercent(), "UP", "PERCENT", true));

        List<ReportColumn> columns = new ArrayList<>(List.of(new ReportColumn("line", "", TEXT),
            new ReportColumn("now", now.label(), TEXT)));
        if (before != null) columns.add(new ReportColumn("before", before.label(), TEXT));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("Average days from registration to a decision", t.toDecide(), w == null ? null : w.toDecide()));
        rows.add(row("Average days from approval to payment", t.approvalToPay(), w == null ? null : w.approvalToPay()));
        rows.add(row("Average days from registration to payment", t.registrationToPay(), w == null ? null : w.registrationToPay()));
        rows.add(row("Claims paid", BigDecimal.valueOf(t.paid()), w == null ? null : BigDecimal.valueOf(w.paid())));
        rows.add(row("Paid within " + ClaimsReporting.TARGET_DAYS + " days (%)", t.onTimePercent(),
            w == null ? null : w.onTimePercent()));

        List<ReportColumn> lateColumns = List.of(new ReportColumn("line", "Claim", TEXT),
            new ReportColumn("days", "Days to pay", COUNT), new ReportColumn("amount", "Paid", MONEY));
        List<Map<String, String>> late = new ArrayList<>();
        for (ClaimFact c : claims) {
            if (!now.holds(c.settledOn()) || c.registeredOn() == null) continue;
            long days = ChronoUnit.DAYS.between(c.registeredOn(), c.settledOn());
            if (days > ClaimsReporting.TARGET_DAYS) {
                Map<String, String> r = new LinkedHashMap<>();
                r.put("line", c.policyNumber() + " · " + ClaimsReporting.typeName(c.claimType()) + " · registered "
                    + c.registeredOn() + " · paid " + c.settledOn());
                r.put("days", Long.toString(days));
                r.put("amount", Figures.money(c.paidAmount()));
                late.add(r);
            }
        }
        return new ReportBody(List.of(
            new ReportSection("Time taken", columns, rows, Map.of()),
            new ReportSection("Paid later than " + ClaimsReporting.TARGET_DAYS + " days", lateColumns, late, Map.of())),
            figures, null, StatementReports.comparisonLabel(f.comparisonMonths()));
    }

    private record Times(BigDecimal toDecide, BigDecimal approvalToPay, BigDecimal registrationToPay, long paid,
                         BigDecimal onTimePercent) {}

    private static Times times(List<ClaimFact> claims, Span span) {
        List<Long> decide = new ArrayList<>();
        List<Long> approvalToPay = new ArrayList<>();
        List<Long> registrationToPay = new ArrayList<>();
        long onTime = 0;
        for (ClaimFact c : claims) {
            LocalDate decided = c.approvedOn() != null ? c.approvedOn() : c.rejectedOn();
            if (span.holds(decided) && c.registeredOn() != null) decide.add(ChronoUnit.DAYS.between(c.registeredOn(), decided));
            if (span.holds(c.settledOn())) {
                if (c.approvedOn() != null) approvalToPay.add(ChronoUnit.DAYS.between(c.approvedOn(), c.settledOn()));
                if (c.registeredOn() != null) {
                    long d = ChronoUnit.DAYS.between(c.registeredOn(), c.settledOn());
                    registrationToPay.add(d);
                    if (d <= ClaimsReporting.TARGET_DAYS) onTime++;
                }
            }
        }
        return new Times(avg(decide), avg(approvalToPay), avg(registrationToPay), registrationToPay.size(),
            registrationToPay.isEmpty() ? null : BigDecimal.valueOf(onTime * 100L)
                .divide(BigDecimal.valueOf(registrationToPay.size()), 1, RoundingMode.HALF_UP));
    }

    private static BigDecimal avg(List<Long> days) {
        if (days.isEmpty()) return null;
        return BigDecimal.valueOf(days.stream().mapToLong(Long::longValue).sum())
            .divide(BigDecimal.valueOf(days.size()), 1, RoundingMode.HALF_UP);
    }

    private static Map<String, String> row(String label, BigDecimal now, BigDecimal before) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("line", label);
        r.put("now", now == null ? "" : now.toPlainString());
        r.put("before", before == null ? "" : before.toPlainString());
        return r;
    }
}
