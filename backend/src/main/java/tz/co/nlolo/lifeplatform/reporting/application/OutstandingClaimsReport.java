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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.ClaimsReporting.Span;

/**
 * C8, the open claims from the start of the period to its end (2026-11-09): open at the start, plus registered, less
 * paid and declined, is open at the end -- with the sum stated, so the movement can be checked line by line.
 */
@Component
public class OutstandingClaimsReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("claims-outstanding", "Outstanding claims movement",
        "Open claims at the start, plus those registered, less those paid and declined, gives the open claims at the end;"
            + " against the period before.",
        ReportCategory.CLAIMS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods();

    private final ReportQueries queries;

    public OutstandingClaimsReport(ReportQueries queries) {
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

        Movement m = movement(claims, now);
        Movement w = before == null ? null : movement(claims, before);

        List<ReportColumn> columns = new ArrayList<>(List.of(new ReportColumn("line", "", TEXT),
            new ReportColumn("count", now.label(), COUNT)));
        if (before != null) columns.add(new ReportColumn("priorCount", before.label(), COUNT));
        List<Map<String, String>> rows = new ArrayList<>();
        rows.add(row("Open at the start", m.opening(), w == null ? null : w.opening(), "LINE"));
        rows.add(row("plus registered", m.registered(), w == null ? null : w.registered(), "LINE"));
        rows.add(row("less paid", m.paid(), w == null ? null : w.paid(), "LINE"));
        rows.add(row("less declined", m.declined(), w == null ? null : w.declined(), "LINE"));
        rows.add(row("Open at the end", m.closing(), w == null ? null : w.closing(), "TOTAL"));
        boolean adds = m.opening() + m.registered() - m.paid() - m.declined() == m.closing();
        Map<String, String> check = new LinkedHashMap<>();
        check.put("line", (adds ? "✓ " : "Does not add up: ") + m.opening() + " + " + m.registered() + " − " + m.paid()
            + " − " + m.declined() + " = " + m.closing());
        rows.add(check);

        BigDecimal unpaid = approvedUnpaid(claims, now.to());
        BigDecimal unpaidWas = before == null ? null : approvedUnpaid(claims, before.to());
        List<ReportFigure> figures = List.of(
            ClaimsReporting.count("closing", "Open at the end", m.closing(), w == null ? null : w.closing(), "DOWN", true),
            ClaimsReporting.count("registered", "Registered", m.registered(), w == null ? null : w.registered(), null, true),
            ClaimsReporting.money("unpaid", "Approved, not yet paid", unpaid, unpaidWas, "DOWN", true));
        return new ReportBody(List.of(new ReportSection("Open claims, start to end", columns, rows, Map.of())),
            figures, null, StatementReports.comparisonLabel(f.comparisonMonths()));
    }

    private record Movement(long opening, long registered, long paid, long declined, long closing) {}

    private static Movement movement(List<ClaimFact> claims, Span span) {
        LocalDate dayBefore = span.from().minusDays(1);
        long opening = claims.stream().filter(c -> ClaimOutcomesReport.openOn(c, dayBefore)).count();
        long registered = claims.stream().filter(c -> span.holds(c.registeredOn())).count();
        long paid = claims.stream().filter(c -> span.holds(c.settledOn()) && c.registeredOn() != null
            && !c.registeredOn().isAfter(span.to())).count();
        long declined = claims.stream().filter(c -> span.holds(c.rejectedOn()) && c.registeredOn() != null
            && !c.registeredOn().isAfter(span.to())).count();
        long closing = claims.stream().filter(c -> ClaimOutcomesReport.openOn(c, span.to())).count();
        return new Movement(opening, registered, paid, declined, closing);
    }

    private static BigDecimal approvedUnpaid(List<ClaimFact> claims, LocalDate day) {
        return claims.stream()
            .filter(c -> c.approvedOn() != null && !c.approvedOn().isAfter(day) && (c.settledOn() == null || c.settledOn().isAfter(day)))
            .map(c -> c.approvedAmount() == null ? BigDecimal.ZERO : c.approvedAmount())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static Map<String, String> row(String label, long now, Long before, String kind) {
        Map<String, String> r = new LinkedHashMap<>();
        r.put("line", label);
        r.put("count", Figures.count(now));
        if (before != null) r.put("priorCount", Figures.count(before));
        r.put("_kind", kind);
        return r;
    }
}
