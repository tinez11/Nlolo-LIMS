package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.PolicyListRow;
import tz.co.nlolo.lifeplatform.reporting.infrastructure.ReportQueries.ProductRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.COUNT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.DATE;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Each product's book on a date, and the policies behind it.
 *
 * <p>"Policies in force" already totals the book by product. What it cannot show is WHICH policies make up a
 * product: this report's second section lists them, one row each, with the client, the status the policy held on
 * the date, its cover, premium, agent and branch. Narrowed by the product filter, it is that product's register.
 *
 * <p>A policy belongs here once it was activated on or before the date; an offer that never went on cover is not
 * a policy of the product. Statuses are read as of the date, the same way every other report reads them.
 */
@Component
public class ProductsReport implements Report {

    // Not "Products": that is the configuration screen's name, and a report titled the same sat beside it in the
    // menu, the Go-to palette and every breadcrumb.
    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("products", "Policies by product",
        // Shown to the reader as written, so a real dash rather than this codebase's two-hyphen comment style.
        "Each product's book on a date — policies written, in force and lapsed, with their cover and annual premium — "
            + "and every policy behind it with its client, status, cover, premium and agent.",
        ReportCategory.MANAGEMENT, DateFilterKind.AS_OF, List.of(), false);

    private final ReportQueries queries;

    public ProductsReport(ReportQueries queries) {
        this.queries = queries;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        return List.of(summary(queries.productSummary(tenantId, f.asOf(), f), f, labels),
            register(queries.policyList(tenantId, f.asOf(), f), f, labels));
    }

    private static ReportSection summary(List<ProductRow> found, ReportFilter f, Labels labels) {
        List<ReportColumn> columns = List.of(col("product", "Product", TEXT), col("category", "Category", TEXT),
            col("currency", "Currency", TEXT), col("written", "Policies written", COUNT), col("inForce", "In force", COUNT),
            col("lapsed", "Lapsed", COUNT), col("sumAssured", "Sum assured in force", MONEY),
            col("annualisedPremium", "Annual premium in force", MONEY),
            col("averagePremium", "Average annual premium", MONEY));

        List<Map<String, String>> rows = new ArrayList<>();
        long regular = 0;
        for (ProductRow p : found) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("product", labels.product(UUID.fromString(p.productId())));
            row.put("category", Labels.words(p.productCategory()));
            row.put("currency", p.currency());
            row.put("written", Figures.count(p.written()));
            row.put("inForce", Figures.count(p.inForce()));
            row.put("lapsed", Figures.count(p.lapsed()));
            row.put("sumAssured", Figures.money(p.sumAssured()));
            row.put("annualisedPremium", Figures.money(p.annualised()));
            row.put("averagePremium", average(p.annualised(), p.regularInForce()));
            rows.add(row);
            regular += p.regularInForce();
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("product")).thenComparing(r -> r.get("currency")));

        // Sections.totals adds up every money column, which for an average is a number that means nothing -- and it
        // cannot read a blank. So it totals everything else, and the overall average is worked out here instead.
        List<ReportColumn> summed = columns.stream().filter(c -> !c.key().equals("averagePremium")).toList();
        Map<String, String> totals = new LinkedHashMap<>(Sections.totals(summed, rows));
        if (!totals.isEmpty()) {
            totals.put("averagePremium", average(new BigDecimal(totals.get("annualisedPremium")), regular));
        }
        return new ReportSection("Products on " + f.asOf(), columns, rows, totals);
    }

    private static ReportSection register(List<PolicyListRow> found, ReportFilter f, Labels labels) {
        List<ReportColumn> columns = List.of(col("policyNumber", "Policy", TEXT), col("product", "Product", TEXT),
            col("client", "Client", TEXT), col("status", "Status", TEXT), col("activatedOn", "On cover from", DATE),
            col("sumAssured", "Sum assured", MONEY), col("premium", "Premium", MONEY),
            col("frequency", "Frequency", TEXT), col("agent", "Agent", TEXT), col("branch", "Branch", TEXT),
            col("currency", "Currency", TEXT));

        List<Map<String, String>> rows = new ArrayList<>();
        for (PolicyListRow p : found) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("policyNumber", p.policyNumber());
            row.put("product", labels.product(UUID.fromString(p.productId())));
            row.put("client", p.holder() == null ? "" : p.holder());
            row.put("status", Labels.words(p.status()));
            row.put("activatedOn", p.activatedOn() == null ? "" : p.activatedOn().toString());
            row.put("sumAssured", Figures.money(p.sumAssured()));
            row.put("premium", Figures.money(p.premium()));
            row.put("frequency", Labels.words(p.frequency()));
            row.put("agent", labels.agent(p.agentId() == null ? null : UUID.fromString(p.agentId())));
            row.put("branch", p.branch() == null ? "" : p.branch());
            row.put("currency", p.currency());
            rows.add(row);
        }
        rows.sort(Comparator.comparing((Map<String, String> r) -> r.get("product")).thenComparing(r -> r.get("policyNumber")));
        // A list, not a summary: adding up premiums paid monthly, yearly and once gives a number nobody can use.
        return new ReportSection("Policies on " + f.asOf(), columns, rows, Map.of());
    }

    /** Annual premium over the policies that pay one; blank when none do, never a division by zero. */
    private static String average(BigDecimal annualised, long regularPolicies) {
        if (regularPolicies == 0 || annualised == null) return "";
        return annualised.divide(BigDecimal.valueOf(regularPolicies), 2, RoundingMode.HALF_UP).toPlainString();
    }
}
