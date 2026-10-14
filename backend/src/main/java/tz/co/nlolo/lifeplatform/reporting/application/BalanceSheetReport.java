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
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * What the entity owns, owes and is worth, through the chosen period.
 *
 * <p>A straight roll-up of the chart's own classes -- 1000 Assets, 2000 Liabilities, 3000 Equity -- and not a
 * statutory presentation (decision D4, 2026-10-14). No account is reclassified, netted or renamed on its way
 * here: what the ledger calls a thing is what this report calls it.
 *
 * <p>Cumulative, through {@code trialBalanceThrough}: a balance sheet states a POSITION. The income statement
 * reads {@code trialBalanceBetween} because it reports movement, and swapping the two produces a plausible
 * statement that is entirely wrong.
 *
 * <p>Comparison takes the MONTH that {@code compareAsOf} falls in. The filter's comparison field is a date
 * because every other comparable report compares dates; the ledger has only months, so the day is dropped
 * rather than a fourth month field added to a filter that every report shares.
 */
@Component
public class BalanceSheetReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("balance-sheet", "Balance sheet",
        "Assets, liabilities and equity through the chosen period, rolled up from the chart of accounts.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH, List.of(), true);

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
        TrialBalanceView ledger = finaccounting.trialBalanceThrough(f.monthFrom().toString());
        TrialBalanceView comparison = f.compareAsOf() == null ? null
            : finaccounting.trialBalanceThrough(YearMonth.from(f.compareAsOf()).toString());

        List<ReportSection> sections = new ArrayList<>();
        Map<String, BigDecimal> classTotals = new LinkedHashMap<>();

        for (String classRoot : LedgerAccounts.BALANCE_SHEET_CLASSES) {
            List<AccountBalanceView> accounts = LedgerAccounts.inClass(ledger.accounts(), classRoot)
                .stream().filter(LedgerAccounts::posted).toList();
            BigDecimal total = accounts.stream().map(AccountBalanceView::balance)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            classTotals.put(classRoot, total);
            sections.add(section(LedgerAccounts.CLASS_NAMES.get(classRoot), accounts, total, comparison));
        }

        BigDecimal assets = classTotals.get("1000");
        BigDecimal liabilitiesAndEquity = classTotals.get("2000").add(classTotals.get("3000"));
        BigDecimal difference = assets.subtract(liabilitiesAndEquity).abs();

        Map<String, String> check = new LinkedHashMap<>();
        check.put("check", difference.signum() == 0
            ? "Assets equal liabilities plus equity: the sheet balances"
            : "Assets do NOT balance liabilities plus equity — the ledger is out, or a period is unposted");
        check.put("difference", Figures.money(difference));
        sections.add(new ReportSection("Accounting equation",
            List.of(col("check", "Check", TEXT), col("difference", "Difference", MONEY)),
            List.of(check), Map.of()));

        return sections;
    }

    private static ReportSection section(String title, List<AccountBalanceView> accounts, BigDecimal total,
                                         TrialBalanceView comparison) {
        List<ReportColumn> columns = comparison == null
            ? List.of(col("accountCode", "Account", TEXT), col("name", "Name", TEXT),
                col("currency", "Currency", TEXT), col("balance", "Balance", MONEY))
            : List.of(col("accountCode", "Account", TEXT), col("name", "Name", TEXT),
                col("currency", "Currency", TEXT), col("balance", "Balance", MONEY),
                col("comparison", "Comparison", MONEY), col("movement", "Movement", MONEY));

        List<Map<String, String>> rows = accounts.stream().map(a -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("accountCode", a.accountCode());
            row.put("name", a.name());
            row.put("currency", a.currency());
            row.put("balance", Figures.money(a.balance()));
            if (comparison != null) {
                BigDecimal was = balanceOf(comparison, a.accountCode());
                row.put("comparison", Figures.money(was));
                row.put("movement", Figures.money(a.balance().subtract(was)));
            }
            return row;
        }).toList();

        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("accountCode", "Total");
        totals.put("name", "");
        totals.put("currency", rows.isEmpty() ? "" : rows.get(0).get("currency"));
        totals.put("balance", Figures.money(total));
        if (comparison != null) {
            BigDecimal was = accounts.stream().map(a -> balanceOf(comparison, a.accountCode()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            totals.put("comparison", Figures.money(was));
            totals.put("movement", Figures.money(total.subtract(was)));
        }
        return new ReportSection(title, columns, rows, totals);
    }

    private static BigDecimal balanceOf(TrialBalanceView view, String accountCode) {
        return view.accounts().stream().filter(a -> a.accountCode().equals(accountCode))
            .map(AccountBalanceView::balance).findFirst().orElse(BigDecimal.ZERO);
    }
}
