package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
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
 * What the entity owns, owes and is worth, through the chosen period -- against the period it is compared with, each
 * line opening to what it is made of (2026-11-09, the reports redesign).
 *
 * <p>A roll-up of the chart's own classes -- 1000 Assets, 2000 Liabilities, 3000 Equity -- and not a statutory
 * presentation (decision D4, 2026-10-14). Each class's lines are the chart's own groups beneath it (1100 Cash and bank,
 * 1200 Financial investments, ...), so nothing is reclassified or renamed on its way here.
 *
 * <p>Cumulative, through {@code trialBalanceThrough}: a balance sheet states a POSITION. The income statement reads
 * {@code trialBalanceBetween} because it reports movement, and swapping the two produces a plausible statement that is
 * entirely wrong.
 *
 * <p>Compared with the prior month or the same month last year ({@code compareWith}), or -- as before the redesign --
 * the month {@code compareAsOf} falls in.
 */
@Component
public class BalanceSheetReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("balance-sheet", "Balance sheet",
        "Assets, liabilities and equity through the chosen period, by the chart's own groups, against the period it is"
            + " compared with.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH, List.of(), true).comparingPeriods().from("LEDGER");

    /** A balance sheet's tree goes back to the ledger's first posting: a position is everything up to the month. */
    private static final String INCEPTION = "1900-01";

    private static final Map<String, String> GOOD_WHEN = Map.of("1000", "UP", "2000", "DOWN", "3000", "UP");

    private final FinaccountingApi finaccounting;

    public BalanceSheetReport(FinaccountingApi finaccounting) {
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
        List<ReportColumn> columns = StatementReports.columns(compares, ledgers.currentLabel(), ledgers.priorLabel());

        List<ReportSection> sections = new ArrayList<>();
        Map<String, Statement.Figures> classFigures = new LinkedHashMap<>();
        for (String classRoot : LedgerAccounts.BALANCE_SHEET_CLASSES) {
            List<Statement.Line> lines = groups(classRoot, ledgers);
            Map<String, Statement.Figures> figures = Statement.evaluate(lines, ledgers.current(), ledgers.prior());
            Statement.Figures total = new Statement.Figures(Statement.account(ledgers.current(), classRoot),
                compares ? Statement.account(ledgers.prior(), classRoot) : null);
            classFigures.put(classRoot, total);

            Map<String, String> totals = StatementReports.row("Total " + LedgerAccounts.CLASS_NAMES.get(classRoot)
                .toLowerCase(java.util.Locale.ROOT), total, compares);
            // The finance dashboard reads the "Assets" and "Liabilities" section totals by these titles, under
            // "balance" (dashboard.application.ReportFigures). Renaming a class section breaks those two cards.
            totals.put("balance", Figures.money(total.current()));
            sections.add(new ReportSection(LedgerAccounts.CLASS_NAMES.get(classRoot), columns,
                StatementReports.rows(lines, figures, compares), totals));
        }

        BigDecimal assets = classFigures.get("1000").current();
        BigDecimal liabilitiesAndEquity = classFigures.get("2000").current().add(classFigures.get("3000").current());
        BigDecimal difference = assets.subtract(liabilitiesAndEquity).abs();
        Map<String, String> check = new LinkedHashMap<>();
        check.put("check", difference.signum() == 0
            ? "Assets equal liabilities plus equity: the sheet balances"
            : "Assets do NOT balance liabilities plus equity — the ledger is out, or a period is unposted");
        check.put("difference", Figures.money(difference));
        sections.add(new ReportSection("Accounting equation",
            List.of(new ReportColumn("check", "Check", TEXT), new ReportColumn("difference", "Difference", MONEY)),
            List.of(check), Map.of()));

        List<ReportFigure> headline = new ArrayList<>();
        for (String classRoot : LedgerAccounts.BALANCE_SHEET_CLASSES) {
            Statement.Line line = new Statement.Line(classRoot, "Total " + LedgerAccounts.CLASS_NAMES.get(classRoot)
                .toLowerCase(java.util.Locale.ROOT), Statement.Kind.TOTAL, List.of(), GOOD_WHEN.get(classRoot));
            headline.add(StatementReports.figure(line, classFigures.get(classRoot), true));
        }
        return new ReportBody(sections, headline, null, comparisonLabel(f));
    }

    @Override
    public ReportParts parts(UUID tenantId, String lineId, ReportFilter f, int page, Labels labels) {
        StatementReports.Ledgers ledgers = ledgers(f);
        List<Statement.Line> lines = new ArrayList<>();
        for (String classRoot : LedgerAccounts.BALANCE_SHEET_CLASSES) {
            lines.addAll(groups(classRoot, ledgers));
        }
        return StatementReports.parts(lineId, lines, ledgers, finaccounting, INCEPTION, f.monthFrom().toString(), page);
    }

    /** A class's lines: the chart's own groups beneath its root that hold anything, in either period. */
    private static List<Statement.Line> groups(String classRoot, StatementReports.Ledgers ledgers) {
        List<Statement.Line> lines = new ArrayList<>();
        for (AccountBalanceView a : ledgers.current()) {
            if (!classRoot.equals(a.parentCode())) continue;
            boolean held = LedgerAccounts.posted(a) || (ledgers.prior() != null && ledgers.prior().stream()
                .anyMatch(p -> p.accountCode().equals(a.accountCode()) && LedgerAccounts.posted(p)));
            if (held) {
                lines.add(Statement.Line.accounts("acct:" + a.accountCode(), a.accountCode() + " " + a.name(),
                    GOOD_WHEN.get(classRoot), a.accountCode()));
            }
        }
        return lines;
    }

    private StatementReports.Ledgers ledgers(ReportFilter f) {
        YearMonth month = f.monthFrom();
        YearMonth compared = f.compareAsOf() != null ? YearMonth.from(f.compareAsOf())
            : f.comparisonMonths() == null ? null : f.comparisonMonths()[0];
        List<AccountBalanceView> current = finaccounting.trialBalanceThrough(month.toString()).accounts();
        List<AccountBalanceView> prior = compared == null ? null
            : finaccounting.trialBalanceThrough(compared.toString()).accounts();
        return new StatementReports.Ledgers(current, prior, "At end of " + StatementReports.span(month, month),
            compared == null ? null : "At end of " + StatementReports.span(compared, compared));
    }

    private static String comparisonLabel(ReportFilter f) {
        if (f.compareAsOf() != null) {
            YearMonth m = YearMonth.from(f.compareAsOf());
            return "compared with the end of " + StatementReports.span(m, m);
        }
        YearMonth[] months = f.comparisonMonths();
        return months == null ? null : "compared with the end of " + StatementReports.span(months[0], months[0]);
    }
}
