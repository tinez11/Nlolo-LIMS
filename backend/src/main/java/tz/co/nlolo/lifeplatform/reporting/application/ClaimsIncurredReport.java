package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.GroupBy;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParts;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.ClaimFact;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Predicate;

import static tz.co.nlolo.lifeplatform.reporting.application.ClaimsReporting.Span;
import static tz.co.nlolo.lifeplatform.reporting.application.ClaimsReporting.Tally;

/**
 * C1, claims incurred against the period before (2026-11-09, the reports redesign batch 2). Claims incurred are claims
 * approved in the period at their approved amount. The change splits into more or fewer claims and bigger or smaller
 * ones, with large claims shown apart; the table opens product, then claim type, then the claims themselves.
 */
@Component
public class ClaimsIncurredReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("claims-incurred", "Claims incurred variance",
        "Claims approved in the period against the period before: how many, how big, by product and claim type, with"
            + " large claims shown apart.",
        ReportCategory.CLAIMS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods().from("REPORTING_COPY");

    private static final String UP_IS_BAD = "DOWN";

    private final ReportQueries queries;

    public ClaimsIncurredReport(ReportQueries queries) {
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
        Tally incurred = ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(now), ClaimFact::approvedAmount);
        Tally was = before == null ? null : ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(before),
            ClaimFact::approvedAmount);

        List<ReportFigure> figures = new ArrayList<>();
        figures.add(ClaimsReporting.money("incurred", "Claims incurred", incurred.amount(),
            was == null ? null : was.amount(), UP_IS_BAD, true));
        figures.add(ClaimsReporting.count("claims", "Number of claims", incurred.count(),
            was == null ? null : was.count(), UP_IS_BAD, true));
        figures.add(ClaimsReporting.money("average", "Average claim", incurred.average(),
            was == null ? null : was.average(), UP_IS_BAD, true));
        for (String type : types(claims)) {
            Predicate<ClaimFact> ofType = c -> type.equals(c.claimType());
            Tally t = ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(now).and(ofType), ClaimFact::approvedAmount);
            Tally p = before == null ? null
                : ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(before).and(ofType), ClaimFact::approvedAmount);
            figures.add(ClaimsReporting.money("type-" + type, ClaimsReporting.typeName(type), t.amount(),
                p == null ? null : p.amount(), UP_IS_BAD, false));
        }
        Tally large = ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(now).and(ClaimsReporting::large),
            ClaimFact::approvedAmount);
        Tally largeWas = before == null ? null : ClaimsReporting.tally(claims,
            ClaimsReporting.approvedIn(before).and(ClaimsReporting::large), ClaimFact::approvedAmount);
        figures.add(ClaimsReporting.count("large", "Large claims (20m or more)", large.count(),
            largeWas == null ? null : largeWas.count(), UP_IS_BAD, false));

        List<java.util.Map<String, String>> rows = new ArrayList<>();
        for (String product : products(claims, now, before)) {
            Predicate<ClaimFact> ofProduct = c -> product.equals(productKey(c));
            java.util.Map<String, String> row = ClaimsReporting.row(labels.dimension(GroupBy.PRODUCT, product),
                ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(now).and(ofProduct), ClaimFact::approvedAmount),
                before == null ? null : ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(before).and(ofProduct),
                    ClaimFact::approvedAmount), UP_IS_BAD);
            row.put("_lineId", "product:" + product);
            row.put("_hasParts", "true");
            row.put("_level", "0");
            row.put("_kind", "LINE");
            rows.add(row);
        }
        java.util.Map<String, String> totals = ClaimsReporting.row("All products", incurred, was, UP_IS_BAD);
        totals.remove("_goodWhen");
        List<ReportSection> sections = List.of(new ReportSection("Claims incurred by product",
            ClaimsReporting.columns("Product", now, before), rows, totals));

        ReportChart chart = null;
        if (was != null) {
            // Large claims apart: the count and severity effects are worked on the claims below the threshold.
            Tally ordinary = new Tally(incurred.count() - large.count(), incurred.amount().subtract(large.amount()));
            Tally ordinaryWas = new Tally(was.count() - largeWas.count(), was.amount().subtract(largeWas.amount()));
            ClaimsReporting.Variance v = ClaimsReporting.Variance.of(ordinary, ordinaryWas);
            BigDecimal largeChange = large.amount().subtract(largeWas.amount());
            chart = new ReportChart("BRIDGE", "What moved claims incurred from " + before.label() + " to " + now.label(),
                List.of(new ReportChart.Bar(StatementReports.shortSpan(f.comparisonMonths()[0], f.comparisonMonths()[1]),
                        Figures.money(was.amount()), "START"),
                    step("More or fewer claims", v.countEffect()),
                    step("Bigger or smaller claims", v.severityEffect()),
                    step("Large claims", largeChange),
                    new ReportChart.Bar(StatementReports.shortSpan(f.monthFrom(), f.monthTo()),
                        Figures.money(incurred.amount()), "END")));
        }
        return new ReportBody(sections, figures, chart, StatementReports.comparisonLabel(f.comparisonMonths()));
    }

    /** A product opens to its claim types, a claim type to its claims (this period's), each level stating its sum. */
    @Override
    public ReportParts parts(UUID tenantId, String lineId, ReportFilter f, int page, Labels labels) {
        Span now = ClaimsReporting.current(f);
        Span before = ClaimsReporting.prior(f);
        List<ClaimFact> claims = queries.claimFacts(tenantId, now.to(), f);
        if (lineId.startsWith("product:")) {
            String product = lineId.substring(8);
            Predicate<ClaimFact> ofProduct = c -> product.equals(productKey(c));
            List<java.util.Map<String, String>> rows = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            for (String type : types(claims)) {
                Predicate<ClaimFact> both = ofProduct.and(c -> type.equals(c.claimType()));
                Tally t = ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(now).and(both), ClaimFact::approvedAmount);
                Tally p = before == null ? null
                    : ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(before).and(both), ClaimFact::approvedAmount);
                if (t.count() == 0 && (p == null || p.count() == 0)) continue;
                java.util.Map<String, String> row = ClaimsReporting.row(ClaimsReporting.typeName(type), t, p, UP_IS_BAD);
                row.put("_lineId", "type:" + product + ":" + type);
                row.put("_hasParts", "true");
                row.put("_level", "1");
                rows.add(row);
                values.add(t.amount());
            }
            BigDecimal total = ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(now).and(ofProduct),
                ClaimFact::approvedAmount).amount();
            return new ReportParts(lineId, labels.dimension(GroupBy.PRODUCT, product),
                ClaimsReporting.columns("Claim type", now, before), rows, rows.size(), 0, rows.size(),
                Statement.check(values, values.stream().map(x -> 1).toList(), total),
                Statement.adds(values, values.stream().map(x -> 1).toList(), total));
        }
        if (lineId.startsWith("type:")) {
            String[] key = lineId.split(":", 3);
            List<ClaimFact> these = claims.stream().filter(ClaimsReporting.approvedIn(now))
                .filter(c -> key[1].equals(productKey(c)) && key[2].equals(c.claimType())).toList();
            List<java.util.Map<String, String>> rows = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            for (ClaimFact c : these) {
                java.util.Map<String, String> row = new java.util.LinkedHashMap<>();
                row.put("line", c.policyNumber() + " · event " + c.dateOfEvent() + " · approved " + c.approvedOn()
                    + (ClaimsReporting.large(c) ? " · large" : ""));
                row.put("count", "1");
                row.put("amount", Figures.money(c.approvedAmount()));
                row.put("_lineId", "claim:" + c.claimId());
                row.put("_level", "2");
                row.put("_source", "claims.Claim:" + c.claimId());
                rows.add(row);
                values.add(c.approvedAmount() == null ? BigDecimal.ZERO : c.approvedAmount());
            }
            BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return new ReportParts(lineId, ClaimsReporting.typeName(key[2]), ClaimsReporting.columns("Claim", now, null),
                rows, rows.size(), 0, rows.size(), rows.size() + (rows.size() == 1 ? " claim" : " claims") + " = "
                    + Statement.money(total), true);
        }
        throw new ReportNotFoundException("claims-incurred line " + lineId);
    }

    private static ReportChart.Bar step(String label, BigDecimal effect) {
        return new ReportChart.Bar(label, Figures.money(effect.abs()), effect.signum() >= 0 ? "UP" : "DOWN");
    }

    private static String productKey(ClaimFact c) {
        return c.productId() == null ? ReportingEventHandlers.UNKNOWN_PRODUCT.toString() : c.productId();
    }

    private static TreeSet<String> types(List<ClaimFact> claims) {
        TreeSet<String> types = new TreeSet<>();
        for (ClaimFact c : claims) if (c.claimType() != null) types.add(c.claimType());
        return types;
    }

    private static TreeSet<String> products(List<ClaimFact> claims, Span now, Span before) {
        TreeSet<String> products = new TreeSet<>();
        for (ClaimFact c : claims) {
            if (now.holds(c.approvedOn()) || (before != null && before.holds(c.approvedOn()))) products.add(productKey(c));
        }
        return products;
    }
}
