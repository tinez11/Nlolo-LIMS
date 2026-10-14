package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * What moved through classes 4000-8000 across a span of periods, and the result it left.
 *
 * <p>MOVEMENT, not position: an income statement reports a span, so this reads {@code trialBalanceBetween}
 * where the balance sheet reads {@code trialBalanceThrough}. The two are easy to swap and the swap produces a
 * plausible statement that is entirely wrong, which is why a test asserts which one is called.
 *
 * <p>A straight roll-up of the chart's classes (decision D4): 4000 revenue, 5000 insurance service expenses,
 * 6000 reinsurance result, 7000 finance and investment result, 8000 other operating expenses and tax. Nothing
 * is reclassified on its way here. Which classes ADD to the result is {@link LedgerAccounts#isIncomeClass}'s
 * answer, decided by class rather than by sampling an account's type.
 */
@Component
public class IncomeStatementReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("income-statement", "Income statement",
        "Revenue and expenses across a span of periods, rolled up from the chart of accounts, and the result.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH_RANGE, List.of(), false);

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
        TrialBalanceView ledger = finaccounting.trialBalanceBetween(f.monthFrom().toString(), f.monthTo().toString());

        List<ReportSection> sections = new ArrayList<>();
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;

        for (String classRoot : LedgerAccounts.INCOME_STATEMENT_CLASSES) {
            // Leaves as rows, the class root's rolled balance as the total: a summary account's balance already
            // contains everything beneath it, so counting both reports each posting once per ancestor.
            List<AccountBalanceView> accounts = LedgerAccounts.postedLeavesIn(ledger.accounts(), classRoot);
            BigDecimal total = LedgerAccounts.classTotal(ledger.accounts(), classRoot);
            if (LedgerAccounts.isIncomeClass(classRoot)) {
                income = income.add(total);
            } else {
                expense = expense.add(total);
            }
            sections.add(section(LedgerAccounts.CLASS_NAMES.get(classRoot), accounts, total));
        }

        BigDecimal result = income.subtract(expense);
        Map<String, String> row = new LinkedHashMap<>();
        row.put("outcome", result.signum() >= 0 ? "Profit for the period" : "Loss for the period");
        row.put("amount", Figures.money(result.abs()));
        // The finance dashboard reads this section's title, "outcome" and "amount" (dashboard.application.ReportFigures).
        sections.add(new ReportSection("Result",
            List.of(col("outcome", "Outcome", TEXT), col("amount", "Amount", MONEY)), List.of(row), Map.of()));

        return sections;
    }

    private static ReportSection section(String title, List<AccountBalanceView> accounts, BigDecimal total) {
        List<ReportColumn> columns = List.of(col("accountCode", "Account", TEXT), col("name", "Name", TEXT),
            col("currency", "Currency", TEXT), col("amount", "Amount", MONEY));
        List<Map<String, String>> rows = accounts.stream().map(a -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("accountCode", a.accountCode());
            row.put("name", a.name());
            row.put("currency", a.currency());
            row.put("amount", Figures.money(a.balance()));
            return row;
        }).toList();
        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("accountCode", "Total");
        totals.put("name", "");
        totals.put("currency", rows.isEmpty() ? "" : rows.get(0).get("currency"));
        totals.put("amount", Figures.money(total));
        return new ReportSection(title, columns, rows, totals);
    }
}
