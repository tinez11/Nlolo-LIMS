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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.ClaimsReporting.Span;
import static tz.co.nlolo.lifeplatform.reporting.application.ClaimsReporting.Tally;

/**
 * C3, what became of claims against the period before (2026-11-09): registered, approved, declined, and still open at
 * the end -- and why the declined were declined.
 */
@Component
public class ClaimOutcomesReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("claim-outcomes", "Claim outcomes",
        "Claims registered, approved and declined in the period, those still open at its end, and the reasons for"
            + " declining, against the period before.",
        ReportCategory.CLAIMS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods().from("REPORTING_COPY");

    private final ReportQueries queries;

    public ClaimOutcomesReport(ReportQueries queries) {
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

        record Outcome(String label, Predicate<ClaimFact> nowTest, Predicate<ClaimFact> beforeTest, String goodWhen) {}
        List<Outcome> outcomes = List.of(
            new Outcome("Registered", c -> now.holds(c.registeredOn()), c -> before != null && before.holds(c.registeredOn()), null),
            new Outcome("Approved", c -> now.holds(c.approvedOn()), c -> before != null && before.holds(c.approvedOn()), null),
            new Outcome("Declined", c -> now.holds(c.rejectedOn()), c -> before != null && before.holds(c.rejectedOn()), "DOWN"),
            new Outcome("Still open at the end", c -> openOn(c, now.to()), c -> before != null && openOn(c, before.to()), "DOWN"));

        List<Map<String, String>> rows = new ArrayList<>();
        for (Outcome o : outcomes) {
            Tally t = ClaimsReporting.tally(claims, o.nowTest(), ClaimFact::approvedAmount);
            Tally p = before == null ? null : ClaimsReporting.tally(claims, o.beforeTest(), ClaimFact::approvedAmount);
            rows.add(ClaimsReporting.row(o.label(), t, p, o.goodWhen()));
        }

        long registered = count(claims, c -> now.holds(c.registeredOn()));
        long declined = count(claims, c -> now.holds(c.rejectedOn()));
        long decided = declined + count(claims, c -> now.holds(c.approvedOn()));
        BigDecimal declineRate = rate(declined, decided);
        BigDecimal declineRateWas = null;
        Long registeredWas = null;
        Long declinedWas = null;
        if (before != null) {
            registeredWas = count(claims, c -> before.holds(c.registeredOn()));
            declinedWas = count(claims, c -> before.holds(c.rejectedOn()));
            declineRateWas = rate(declinedWas, declinedWas + count(claims, c -> before.holds(c.approvedOn())));
        }
        long open = count(claims, c -> openOn(c, now.to()));
        Long openWas = before == null ? null : count(claims, c -> openOn(c, before.to()));

        List<ReportFigure> figures = List.of(
            ClaimsReporting.count("registered", "Claims registered", registered, registeredWas, null, true),
            ClaimsReporting.ratio("declineRate", "Declined of those decided", declineRate, declineRateWas, "DOWN", "PERCENT", true),
            ClaimsReporting.count("open", "Still open at the end", open, openWas, "DOWN", true),
            ClaimsReporting.count("declined", "Declined", declined, declinedWas, "DOWN", false));

        // Why: the reasons as given, counted.
        Map<String, long[]> reasons = new TreeMap<>();
        for (ClaimFact c : claims) {
            String reason = c.rejectReason() == null || c.rejectReason().isBlank() ? "No reason recorded" : c.rejectReason().trim();
            if (now.holds(c.rejectedOn())) reasons.computeIfAbsent(reason, r -> new long[2])[0]++;
            if (before != null && before.holds(c.rejectedOn())) reasons.computeIfAbsent(reason, r -> new long[2])[1]++;
        }
        List<ReportColumn> reasonColumns = new ArrayList<>(List.of(new ReportColumn("line", "Reason", TEXT),
            new ReportColumn("count", "Declined " + now.label(), COUNT)));
        if (before != null) reasonColumns.add(new ReportColumn("priorCount", "Declined " + before.label(), COUNT));
        List<Map<String, String>> reasonRows = new ArrayList<>();
        reasons.forEach((reason, n) -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("line", reason);
            row.put("count", Figures.count(n[0]));
            if (before != null) row.put("priorCount", Figures.count(n[1]));
            reasonRows.add(row);
        });

        return new ReportBody(List.of(
            new ReportSection("What became of claims", ClaimsReporting.columns("Outcome", now, before), rows, Map.of()),
            new ReportSection("Why claims were declined", reasonColumns, reasonRows, Map.of())),
            figures, null, StatementReports.comparisonLabel(f.comparisonMonths()));
    }

    /**
     * Open on a day: registered by then, and not closed by then -- closed being paid or declined, unless the claim was
     * reopened on or after that closing and by that day (2026-11-09: a reopened claim is open again).
     */
    static boolean openOn(ClaimFact c, LocalDate day) {
        if (c.registeredOn() == null || c.registeredOn().isAfter(day)) return false;
        LocalDate closed = null;
        if (c.settledOn() != null && !c.settledOn().isAfter(day)) closed = c.settledOn();
        if (c.rejectedOn() != null && !c.rejectedOn().isAfter(day) && (closed == null || c.rejectedOn().isAfter(closed))) {
            closed = c.rejectedOn();
        }
        if (closed == null) return true;
        return c.reopenedOn() != null && !c.reopenedOn().isAfter(day) && !c.reopenedOn().isBefore(closed);
    }

    private static long count(List<ClaimFact> claims, Predicate<ClaimFact> which) {
        return claims.stream().filter(which).count();
    }

    private static BigDecimal rate(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }
}
