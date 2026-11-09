package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportChart;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParts;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/**
 * What moved through classes 4000-8000 across a span of periods, and the result it left -- presented the IFRS 17 way
 * with subtotals, against the period it is compared with, and with every line opening to what it is made of
 * (2026-11-09, the reports redesign).
 *
 * <p>MOVEMENT, not position: an income statement reports a span, so this reads {@code trialBalanceBetween} where the
 * balance sheet reads {@code trialBalanceThrough}. The two are easy to swap and the swap produces a plausible statement
 * that is entirely wrong, which is why a test asserts which one is called.
 *
 * <p>Classes roll up as the chart has them (decision D4): 4000 revenue, 5000 insurance service expenses, 6000 the net
 * expense of reinsurance held, 7000 finance and investment, 8000 other expenses and tax. 4000 and 7000 add to the
 * result; the others subtract ({@link LedgerAccounts#isIncomeClass}).
 */
@Component
public class IncomeStatementReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("income-statement", "Income statement",
        "Revenue and expenses across a span of periods, with the insurance service result and the profit, against the"
            + " period it is compared with.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH_RANGE, List.of(), false).comparingPeriods();

    static final List<Statement.Line> LINES = List.of(
        Statement.Line.accounts("revenue", "Insurance revenue", "UP", "4000"),
        Statement.Line.accounts("service-expenses", "Insurance service expenses", "DOWN", "5000"),
        Statement.Line.subtotal("service-result", "Insurance service result", "UP",
            new Statement.Part("line:revenue", 1), new Statement.Part("line:service-expenses", -1)),
        Statement.Line.accounts("reinsurance", "Net expense from reinsurance contracts held", "DOWN", "6000"),
        Statement.Line.accounts("finance", "Finance and investment result", "UP", "7000"),
        Statement.Line.accounts("other", "Other operating expenses and tax", "DOWN", "8000"),
        Statement.Line.total("profit", "Profit for the period", "UP",
            new Statement.Part("line:service-result", 1), new Statement.Part("line:reinsurance", -1),
            new Statement.Part("line:finance", 1), new Statement.Part("line:other", -1)));

    /** The lines the bridge walks through, and whether each adds to the profit or takes from it. */
    private static final List<Statement.Part> MOVERS = List.of(new Statement.Part("revenue", 1),
        new Statement.Part("service-expenses", -1), new Statement.Part("reinsurance", -1),
        new Statement.Part("finance", 1), new Statement.Part("other", -1));

    private final FinaccountingApi finaccounting;

    public IncomeStatementReport(FinaccountingApi finaccounting) {
        this.finaccounting = finaccounting;
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
        StatementReports.Ledgers ledgers = ledgers(f);
        boolean compares = ledgers.prior() != null;
        Map<String, Statement.Figures> figures = Statement.evaluate(LINES, ledgers.current(), ledgers.prior());

        List<ReportSection> sections = new ArrayList<>();
        sections.add(new ReportSection("Income statement",
            StatementReports.columns(compares, ledgers.currentLabel(), ledgers.priorLabel()),
            StatementReports.rows(LINES, figures, compares), Map.of()));

        // Read by the finance dashboard (dashboard.application.ReportFigures): its title, "outcome" and "amount".
        BigDecimal result = figures.get("profit").current();
        Map<String, String> row = new LinkedHashMap<>();
        row.put("outcome", result.signum() >= 0 ? "Profit for the period" : "Loss for the period");
        row.put("amount", Figures.money(result.abs()));
        sections.add(new ReportSection("Result",
            List.of(new ReportColumn("outcome", "Outcome", TEXT), new ReportColumn("amount", "Amount", MONEY)),
            List.of(row), Map.of()));

        List<ReportFigure> headline = new ArrayList<>();
        for (String id : List.of("revenue", "service-result", "profit")) {
            headline.add(StatementReports.figure(line(id), figures.get(id), true));
        }
        return new ReportBody(sections, headline, compares ? bridge(figures, ledgers) : null,
            StatementReports.comparisonLabel(f.comparisonMonths()));
    }

    @Override
    public ReportParts parts(UUID tenantId, String lineId, ReportFilter f, int page, Labels labels) {
        return StatementReports.parts(lineId, LINES, ledgers(f), finaccounting, f.monthFrom().toString(),
            f.monthTo().toString(), page);
    }

    /** From the compared period's profit to this period's, one bar per line's effect on the profit. */
    private static ReportChart bridge(Map<String, Statement.Figures> figures, StatementReports.Ledgers ledgers) {
        List<ReportChart.Bar> bars = new ArrayList<>();
        bars.add(new ReportChart.Bar(ledgers.priorLabel(), Figures.money(figures.get("profit").prior()), "START"));
        for (Statement.Part mover : MOVERS) {
            BigDecimal effect = figures.get(mover.ref()).change();
            if (mover.sign() < 0) effect = effect.negate();
            bars.add(new ReportChart.Bar(line(mover.ref()).label(), Figures.money(effect.abs()),
                effect.signum() >= 0 ? "UP" : "DOWN"));
        }
        bars.add(new ReportChart.Bar(ledgers.currentLabel(), Figures.money(figures.get("profit").current()), "END"));
        return new ReportChart("BRIDGE", "How the profit moved from " + ledgers.priorLabel() + " to "
            + ledgers.currentLabel() + ", line by line", bars);
    }

    private StatementReports.Ledgers ledgers(ReportFilter f) {
        List<AccountBalanceView> current = finaccounting.trialBalanceBetween(f.monthFrom().toString(),
            f.monthTo().toString()).accounts();
        YearMonth[] compared = f.comparisonMonths();
        List<AccountBalanceView> prior = compared == null ? null
            : finaccounting.trialBalanceBetween(compared[0].toString(), compared[1].toString()).accounts();
        return new StatementReports.Ledgers(current, prior, StatementReports.span(f.monthFrom(), f.monthTo()),
            compared == null ? null : StatementReports.span(compared[0], compared[1]));
    }

    private static Statement.Line line(String id) {
        return LINES.stream().filter(l -> l.id().equals(id)).findFirst().orElseThrow();
    }
}
