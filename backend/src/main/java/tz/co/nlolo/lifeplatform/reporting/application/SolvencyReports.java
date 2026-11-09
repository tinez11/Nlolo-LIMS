package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportNotFoundException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParts;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/** What the solvency reports share: the book a month is read from, the factors it is read with, the tree. */
@Component
class SolvencyReports {

    static final String PLACEHOLDER_NOTICE = "Placeholder risk charges, not TIRA's: the factors are stand-ins until"
        + " TIRA's Risk-Based Capital guideline is loaded on the Solvency factors page.";

    private final FinaccountingApi finaccounting;
    private final SolvencyFactorSets factorSets;

    SolvencyReports(FinaccountingApi finaccounting, SolvencyFactorSets factorSets) {
        this.finaccounting = finaccounting;
        this.factorSets = factorSets;
    }

    /** The month as the calculation reads it: positions at its end, insurance revenue over the twelve months to it. */
    Solvency.Book book(YearMonth month) {
        List<AccountBalanceView> ledger = finaccounting.trialBalanceThrough(month.toString()).accounts();
        YearMonth first = month.minusMonths(11);
        List<AccountBalanceView> year = finaccounting.trialBalanceBetween(first.toString(), month.toString()).accounts();
        return new Solvency.Book(ledger, LedgerAccounts.classTotal(year, "4000"), StatementReports.shortSpan(first, month));
    }

    SolvencyFactorSetView factors(UUID tenantId, YearMonth month) {
        return factorSets.forMonth(tenantId, month);
    }

    /** The strip every solvency report carries while the factors are stand-ins. */
    static List<String> notices(SolvencyFactorSetView set, String... more) {
        List<String> out = new ArrayList<>();
        if (set.placeholder()) out.add(PLACEHOLDER_NOTICE);
        out.addAll(List.of(more));
        return out;
    }

    /** Which factors the figures were worked out with -- so a printed report can be re-run on the same ones. */
    static ReportSection factorSection(SolvencyFactorSetView set) {
        Map<String, String> row = new LinkedHashMap<>();
        row.put("version", set.version() == 0 ? "Built in" : "Version " + set.version());
        row.put("name", set.name());
        row.put("effectiveFrom", set.version() == 0 ? "Always" : set.effectiveFrom().toString());
        row.put("placeholder", set.placeholder() ? "Placeholder" : "Approved factors");
        row.put("createdBy", set.createdBy() == null ? "" : set.createdBy());
        return new ReportSection("Factors used", List.of(new ReportColumn("version", "Version", TEXT),
            new ReportColumn("name", "Name", TEXT), new ReportColumn("effectiveFrom", "In force from", TEXT),
            new ReportColumn("placeholder", "Status", TEXT), new ReportColumn("createdBy", "Published by", TEXT)),
            List.of(row), Map.of());
    }

    /** Rows of the tree at one level: each node, with its prior twin's figure when comparing. */
    static List<Map<String, String>> rows(List<Solvency.Node> nodes, Solvency.Node priorTree, boolean compares,
                                          int level, String kind) {
        List<Map<String, String>> rows = new ArrayList<>();
        for (Solvency.Node n : nodes) {
            BigDecimal before = compares && priorTree != null
                ? priorTree.find(n.id()).map(Solvency.Node::value).orElse(BigDecimal.ZERO) : null;
            Map<String, String> row = StatementReports.row(n.label(), new Statement.Figures(n.value(), before), compares);
            row.put("_lineId", n.id());
            row.put("_hasParts", Boolean.toString(!n.children().isEmpty()));
            row.put("_level", Integer.toString(level));
            row.put("_kind", kind);
            String good = goodWhen(n.id());
            if (good != null) row.put("_goodWhen", good);
            rows.add(row);
        }
        return rows;
    }

    /** What one node is made of, and the server's own statement that it adds up. */
    static ReportParts parts(String lineId, Solvency.Node tree, Solvency.Node priorTree, List<ReportColumn> columns) {
        Solvency.Node node = tree.find(lineId).orElseThrow(() -> new ReportNotFoundException("line " + lineId));
        boolean compares = columns.stream().anyMatch(c -> c.key().equals("prior"));
        List<Map<String, String>> rows = rows(node.children(), priorTree, compares, 1, "LINE");
        List<BigDecimal> values = new ArrayList<>();
        List<Integer> signs = new ArrayList<>();
        for (Solvency.Node c : node.children()) {
            values.add(c.value().abs());
            signs.add(c.value().signum() < 0 ? -1 : 1);
        }
        return new ReportParts(lineId, node.label(), columns, rows, rows.size(), 0, rows.size(),
            Statement.check(values, signs, node.value()), Statement.adds(values, signs, node.value()));
    }

    /** Capital is good news when it rises; a charge, when it falls. */
    private static String goodWhen(String id) {
        if (id.startsWith("available") || id.startsWith("over") || id.equals("equity") || id.equals("result")) return "UP";
        if (id.startsWith("required") || id.startsWith("risk:") || id.startsWith("charge:")) return "DOWN";
        return null;
    }

    static String money(BigDecimal value) {
        return Figures.money(value);
    }
}
