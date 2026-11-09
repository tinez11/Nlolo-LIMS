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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.ClaimsReporting.Span;

/**
 * C2, the loss ratio against the period before (2026-11-09): claims incurred ÷ premium collected, by product. Premium
 * collected rather than earned: the ledger's earned premium is not split by product where reporting can read it, and
 * a ratio whose two halves come from different books would not add up to anything.
 */
@Component
public class LossRatioReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("loss-ratio", "Loss ratio variance",
        "Claims incurred as a share of premium collected, by product, against the period before.",
        ReportCategory.CLAIMS, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods();

    private final ReportQueries queries;

    public LossRatioReport(ReportQueries queries) {
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
        Map<String, BigDecimal> premium = queries.collectedByProduct(tenantId, now.from(), now.to(), f);
        Map<String, BigDecimal> premiumWas = before == null ? Map.of()
            : queries.collectedByProduct(tenantId, before.from(), before.to(), f);

        TreeSet<String> products = new TreeSet<>(premium.keySet());
        products.addAll(premiumWas.keySet());
        for (ClaimFact c : claims) {
            if (now.holds(c.approvedOn()) || (before != null && before.holds(c.approvedOn()))) products.add(key(c));
        }

        List<ReportColumn> columns = new ArrayList<>(List.of(new ReportColumn("line", "Product", TEXT),
            new ReportColumn("claims", "Claims incurred", MONEY), new ReportColumn("premium", "Premium collected", MONEY),
            new ReportColumn("ratio", "Loss ratio", PERCENT)));
        if (before != null) {
            columns.add(new ReportColumn("priorRatio", "Loss ratio " + before.label(), PERCENT));
            columns.add(new ReportColumn("pointChange", "Change (points)", PERCENT));
        }
        List<Map<String, String>> rows = new ArrayList<>();
        for (String product : products) {
            BigDecimal c = incurred(claims, now, product);
            BigDecimal p = premium.getOrDefault(product, BigDecimal.ZERO);
            BigDecimal r = ratio(c, p);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("line", labels.dimension(GroupBy.PRODUCT, product));
            row.put("claims", Figures.money(c));
            row.put("premium", Figures.money(p));
            row.put("ratio", r == null ? "" : r.toPlainString());
            if (before != null) {
                BigDecimal rw = ratio(incurred(claims, before, product), premiumWas.getOrDefault(product, BigDecimal.ZERO));
                row.put("priorRatio", rw == null ? "" : rw.toPlainString());
                row.put("pointChange", r == null || rw == null ? "" : r.subtract(rw).toPlainString());
            }
            rows.add(row);
        }

        BigDecimal totalClaims = incurred(claims, now, null);
        BigDecimal totalPremium = premium.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ratio = ratio(totalClaims, totalPremium);
        BigDecimal claimsWas = before == null ? null : incurred(claims, before, null);
        BigDecimal premiumBefore = before == null ? null : premiumWas.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal ratioWas = before == null ? null : ratio(claimsWas, premiumBefore);

        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("line", "All products");
        totals.put("claims", Figures.money(totalClaims));
        totals.put("premium", Figures.money(totalPremium));
        totals.put("ratio", ratio == null ? "" : ratio.toPlainString());
        if (before != null) {
            totals.put("priorRatio", ratioWas == null ? "" : ratioWas.toPlainString());
            totals.put("pointChange", ratio == null || ratioWas == null ? "" : ratio.subtract(ratioWas).toPlainString());
        }

        List<ReportFigure> figures = List.of(
            ClaimsReporting.ratio("ratio", "Loss ratio", ratio, ratioWas, "DOWN", "PERCENT", true),
            ClaimsReporting.money("claims", "Claims incurred", totalClaims, claimsWas, "DOWN", true),
            ClaimsReporting.money("premium", "Premium collected", totalPremium, premiumBefore, "UP", true));
        return new ReportBody(List.of(new ReportSection("Loss ratio by product", columns, rows, totals)), figures, null,
            StatementReports.comparisonLabel(f.comparisonMonths()));
    }

    private static BigDecimal incurred(List<ClaimFact> claims, Span span, String product) {
        return ClaimsReporting.tally(claims, ClaimsReporting.approvedIn(span)
            .and(c -> product == null || product.equals(key(c))), ClaimFact::approvedAmount).amount();
    }

    /** Claims ÷ premium as a percentage, one decimal; null when nothing was collected. */
    static BigDecimal ratio(BigDecimal claims, BigDecimal premium) {
        if (premium == null || premium.signum() == 0) return null;
        return claims.multiply(BigDecimal.valueOf(100)).divide(premium, 1, RoundingMode.HALF_UP);
    }

    private static String key(ClaimFact c) {
        return c.productId() == null ? ReportingEventHandlers.UNKNOWN_PRODUCT.toString() : c.productId();
    }
}
