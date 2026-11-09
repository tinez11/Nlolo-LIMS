package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.ClaimsReporting.Span;

/**
 * C6, claims early in a policy's life against the period before (2026-11-09): claims registered in the period whose
 * event fell within a year, or two, of the policy's start -- the first sign of weak underwriting or a sale that was
 * not what it seemed -- by agent and branch.
 */
@Component
public class EarlyClaimsReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("early-claims", "Early claims",
        "Claims registered in the period whose event fell within one or two years of the policy starting, by agent and"
            + " branch, against the period before.",
        ReportCategory.CLAIMS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods();

    private final ReportQueries queries;

    public EarlyClaimsReport(ReportQueries queries) {
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

        long all = registered(claims, now).size();
        long year1 = registered(claims, now).stream().filter(c -> within(c, 1)).count();
        long year2 = registered(claims, now).stream().filter(c -> within(c, 2)).count();
        Long year1Was = before == null ? null : registered(claims, before).stream().filter(c -> within(c, 1)).count();
        Long year2Was = before == null ? null : registered(claims, before).stream().filter(c -> within(c, 2)).count();

        List<ReportFigure> figures = List.of(
            ClaimsReporting.count("year1", "Within a year of starting", year1, year1Was, "DOWN", true),
            ClaimsReporting.count("year2", "Within two years", year2, year2Was, "DOWN", true),
            ClaimsReporting.ratio("share", "Share of claims registered",
                all == 0 ? null : BigDecimal.valueOf(year2 * 100L).divide(BigDecimal.valueOf(all), 1, RoundingMode.HALF_UP),
                null, "DOWN", "PERCENT", true));

        List<ReportSection> sections = new ArrayList<>();
        sections.add(by("By agent", claims, now, before, c -> c.agentId() == null ? "DIRECT" : c.agentId(),
            k -> labels.dimension(GroupBy.AGENT, k)));
        sections.add(by("By branch", claims, now, before, c -> c.branchCode() == null ? "UNSPECIFIED" : c.branchCode(),
            k -> labels.dimension(GroupBy.BRANCH, k)));

        List<ReportColumn> listColumns = List.of(new ReportColumn("line", "Claim", TEXT),
            new ReportColumn("days", "Days from start to event", COUNT));
        List<Map<String, String>> list = new ArrayList<>();
        for (ClaimFact c : registered(claims, now)) {
            if (!within(c, 2)) continue;
            Map<String, String> r = new LinkedHashMap<>();
            r.put("line", c.policyNumber() + " · " + ClaimsReporting.typeName(c.claimType()) + " · started "
                + c.activatedOn() + " · event " + c.dateOfEvent());
            r.put("days", Long.toString(ChronoUnit.DAYS.between(c.activatedOn(), c.dateOfEvent())));
            list.add(r);
        }
        sections.add(new ReportSection("The claims", listColumns, list, Map.of()));
        return new ReportBody(sections, figures, null, StatementReports.comparisonLabel(f.comparisonMonths()));
    }

    private static ReportSection by(String title, List<ClaimFact> claims, Span now, Span before,
                                    Function<ClaimFact, String> key, Function<String, String> name) {
        Map<String, long[]> counts = new TreeMap<>();
        for (ClaimFact c : registered(claims, now)) if (within(c, 2)) counts.computeIfAbsent(key.apply(c), k -> new long[2])[0]++;
        if (before != null) {
            for (ClaimFact c : registered(claims, before)) if (within(c, 2)) counts.computeIfAbsent(key.apply(c), k -> new long[2])[1]++;
        }
        List<ReportColumn> columns = new ArrayList<>(List.of(new ReportColumn("line", title.substring(3), TEXT),
            new ReportColumn("count", "Early claims " + now.label(), COUNT)));
        if (before != null) columns.add(new ReportColumn("priorCount", "Early claims " + before.label(), COUNT));
        List<Map<String, String>> rows = new ArrayList<>();
        counts.forEach((k, n) -> {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("line", name.apply(k));
            r.put("count", Figures.count(n[0]));
            if (before != null) r.put("priorCount", Figures.count(n[1]));
            rows.add(r);
        });
        return new ReportSection(title, columns, rows, Map.of());
    }

    private static List<ClaimFact> registered(List<ClaimFact> claims, Span span) {
        return claims.stream().filter(c -> span.holds(c.registeredOn())).toList();
    }

    /** The event fell within {@code years} of the policy starting. */
    private static boolean within(ClaimFact c, int years) {
        return c.activatedOn() != null && c.dateOfEvent() != null && !c.dateOfEvent().isBefore(c.activatedOn())
            && c.dateOfEvent().isBefore(c.activatedOn().plusYears(years));
    }
}
