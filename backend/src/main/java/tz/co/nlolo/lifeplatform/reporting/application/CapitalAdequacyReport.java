package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParts;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactorSetView;
import tz.co.nlolo.lifeplatform.reporting.api.SolvencyFactors;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.PERCENT;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/**
 * Capital adequacy -- risk-based capital -- at a month's end (2026-11-09, the reports redesign batch 3): what capital
 * the company has, what its risks require, the ratio of the two, and where that ratio stands against the regulator's
 * thresholds (MCR, SCR, PCR). Every figure opens to what it is made of, down to an account's balance at its rate.
 *
 * <p>Compared with the month before or the same month last year, each month on the factors in force then.
 */
@Component
public class CapitalAdequacyReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("capital-adequacy",
        "Capital adequacy (RBC)",
        "Available capital against the capital the risks require, the ratio, and how it stands against the minimum"
            + " (MCR), solvency (SCR) and prescribed (PCR) levels.",
        ReportCategory.SOLVENCY, DateFilterKind.MONTH, List.of(), true).comparingPeriods().from("LEDGER");

    private final SolvencyReports solvency;

    public CapitalAdequacyReport(SolvencyReports solvency) {
        this.solvency = solvency;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        return body(tenantId, f, labels).sections();
    }

    private record Run(YearMonth month, SolvencyFactorSetView set, Solvency.Capital now, Solvency.Capital before,
                       List<ReportColumn> columns) {}

    private Run run(UUID tenantId, ReportFilter f) {
        YearMonth month = f.monthFrom();
        YearMonth[] compared = f.comparisonMonths();
        SolvencyFactorSetView set = solvency.factors(tenantId, month);
        Solvency.Capital now = Solvency.compute(solvency.book(month), set.factors());
        Solvency.Capital before = compared == null ? null
            : Solvency.compute(solvency.book(compared[0]), solvency.factors(tenantId, compared[0]).factors());
        List<ReportColumn> columns = StatementReports.columns(before != null,
            "At end of " + StatementReports.span(month, month),
            compared == null ? null : "At end of " + StatementReports.span(compared[0], compared[0]));
        return new Run(month, set, now, before, columns);
    }

    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        Run r = run(tenantId, f);
        boolean compares = r.before() != null;
        Solvency.Node tree = r.now().tree();
        Solvency.Node priorTree = compares ? r.before().tree() : null;

        List<Map<String, String>> rows = new ArrayList<>();
        rows.addAll(SolvencyReports.rows(List.of(r.now().available(), r.now().required()), priorTree, compares, 0, "SUBTOTAL"));
        rows.addAll(SolvencyReports.rows(List.of(tree.children().get(2)), priorTree, compares, 0, "TOTAL"));

        List<ReportSection> sections = new ArrayList<>();
        sections.add(new ReportSection("Capital at end of " + StatementReports.span(r.month(), r.month()), r.columns(),
            rows, Map.of()));
        sections.add(thresholds(r.now(), r.set().factors()));
        sections.add(SolvencyReports.factorSection(r.set()));

        List<ReportFigure> figures = new ArrayList<>();
        figures.add(ClaimsReporting.ratio("ratio", "Capital adequacy ratio", r.now().ratio(),
            compares ? r.before().ratio() : null, "UP", "PERCENT", true));
        figures.add(ClaimsReporting.money("available", "Available capital", r.now().available().value(),
            compares ? r.before().available().value() : null, "UP", true));
        figures.add(ClaimsReporting.money("required", "Required capital", r.now().required().value(),
            compares ? r.before().required().value() : null, "DOWN", true));
        figures.add(ClaimsReporting.money("insurance", "Insurance risk", r.now().insurance(),
            compares ? r.before().insurance() : null, "DOWN", false));
        figures.add(ClaimsReporting.money("market", "Market risk", r.now().market(),
            compares ? r.before().market() : null, "DOWN", false));
        figures.add(ClaimsReporting.money("credit", "Credit risk", r.now().credit(),
            compares ? r.before().credit() : null, "DOWN", false));
        figures.add(ClaimsReporting.money("operational", "Operational risk", r.now().operational(),
            compares ? r.before().operational() : null, "DOWN", false));

        return new ReportBody(sections, figures, chart(r.now(), r.set().factors()),
            compares ? "compared with the end of " + StatementReports.span(f.comparisonMonths()[0], f.comparisonMonths()[0])
                : null,
            SolvencyReports.notices(r.set()));
    }

    @Override
    public ReportParts parts(UUID tenantId, String lineId, ReportFilter f, int page, Labels labels) {
        Run r = run(tenantId, f);
        return SolvencyReports.parts(lineId, r.now().tree(), r.before() == null ? null : r.before().tree(), r.columns());
    }

    /** Each threshold: the capital it needs, what is held over or short of it, and whether it is met. */
    static ReportSection thresholds(Solvency.Capital capital, SolvencyFactors factors) {
        List<ReportColumn> columns = List.of(new ReportColumn("threshold", "Threshold", TEXT),
            new ReportColumn("ratioNeeded", "Ratio needed", PERCENT), new ReportColumn("capitalNeeded", "Capital needed", MONEY),
            new ReportColumn("surplus", "Over (or short of) it", MONEY), new ReportColumn("met", "Met", TEXT));
        List<Map<String, String>> rows = new ArrayList<>();
        for (SolvencyFactors.Threshold t : factors.thresholds()) {
            BigDecimal needed = capital.required().value().multiply(t.ratioPercent())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal over = capital.available().value().subtract(needed);
            Map<String, String> row = new LinkedHashMap<>();
            row.put("threshold", t.label());
            row.put("ratioNeeded", t.ratioPercent().stripTrailingZeros().toPlainString());
            row.put("capitalNeeded", Figures.money(needed));
            row.put("surplus", Figures.money(over));
            row.put("met", over.signum() >= 0 ? "Met" : "NOT met");
            rows.add(row);
        }
        return new ReportSection("Against the thresholds", columns, rows, Map.of());
    }

    /** How the required capital is built: each risk, the allowance for them not striking together, operations. */
    private static ReportChart chart(Solvency.Capital c, SolvencyFactors factors) {
        List<ReportChart.Bar> bars = new ArrayList<>();
        bars.add(new ReportChart.Bar("Insurance", Figures.money(c.insurance()), "START"));
        bars.add(new ReportChart.Bar("Market", Figures.money(c.market()), "UP"));
        bars.add(new ReportChart.Bar("Credit", Figures.money(c.credit()), "UP"));
        if (SolvencyFactors.ROOT_SUM_OF_SQUARES.equals(factors.combination())) {
            bars.add(new ReportChart.Bar("Diversification", Figures.money(c.diversification().negate()), "DOWN"));
        }
        bars.add(new ReportChart.Bar("Operational", Figures.money(c.operational()), "UP"));
        bars.add(new ReportChart.Bar("Required", Figures.money(c.required().value()), "END"));
        return new ReportChart("BRIDGE", "How the required capital is built, risk by risk", bars);
    }
}
