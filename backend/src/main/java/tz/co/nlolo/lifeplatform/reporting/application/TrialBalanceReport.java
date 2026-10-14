package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;
import static tz.co.nlolo.lifeplatform.reporting.application.Sections.col;

/**
 * Every posted account's position through the chosen period, and the one assertion a ledger must always be
 * able to make.
 *
 * <p>Cumulative, not one month's movement: a trial balance states where the books STAND at a date. The rows
 * carry each account's OWN debits and credits -- a trial balance lists what was posted where, and the roll-up
 * belongs to the statements that summarise it.
 *
 * <p>The totals are the ledger's own, not a sum of the rows: {@code TrialBalanceView} computes them from own
 * figures precisely so nothing downstream re-adds rolled balances and reports a sound ledger as wildly out.
 * Whether it balances is likewise the ledger's answer, never a comparison of two decimal strings made here.
 */
@Component
public class TrialBalanceReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("trial-balance", "Trial balance",
        "Every posted account's debits, credits and balance through the chosen period, and whether the ledger balances.",
        ReportCategory.ACCOUNTING, DateFilterKind.MONTH, List.of(), false);

    private final FinaccountingApi finaccounting;

    public TrialBalanceReport(FinaccountingApi finaccounting) {
        this.finaccounting = finaccounting;
    }

    @Override
    public ReportDefinitionView definition() {
        return DEFINITION;
    }

    @Override
    public List<ReportSection> run(UUID tenantId, ReportFilter f, Labels labels) {
        TrialBalanceView ledger = finaccounting.trialBalanceThrough(f.monthFrom().toString());

        List<ReportColumn> columns = List.of(col("accountCode", "Account", TEXT), col("name", "Name", TEXT),
            col("accountType", "Type", TEXT), col("currency", "Currency", TEXT),
            col("debit", "Debit", MONEY), col("credit", "Credit", MONEY), col("balance", "Balance", MONEY));

        // The accounts that were posted TO. A summary account carries no postings of its own, so it would be a
        // row of two zeroes beside a balance it inherited -- and the roll-up belongs to the statements, not here.
        List<Map<String, String>> rows = ledger.accounts().stream()
            .filter(a -> a.postingAllowed() && LedgerAccounts.posted(a)).map(a -> {
            Map<String, String> row = new LinkedHashMap<>();
            row.put("accountCode", a.accountCode());
            row.put("name", a.name());
            row.put("accountType", a.accountType().name());
            row.put("currency", a.currency());
            row.put("debit", Figures.money(a.ownDebit()));
            row.put("credit", Figures.money(a.ownCredit()));
            row.put("balance", Figures.money(a.balance()));
            return row;
        }).toList();

        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("accountCode", "Total");
        totals.put("name", "");
        totals.put("accountType", "");
        totals.put("currency", rows.isEmpty() ? "" : rows.get(0).get("currency"));
        totals.put("debit", Figures.money(ledger.totalDebit()));
        totals.put("credit", Figures.money(ledger.totalCredit()));
        totals.put("balance", "");

        List<ReportColumn> check = List.of(col("status", "Check", TEXT), col("difference", "Difference", MONEY));
        BigDecimal difference = ledger.totalDebit().subtract(ledger.totalCredit()).abs();
        Map<String, String> checkRow = new LinkedHashMap<>();
        checkRow.put("status", ledger.balanced()
            ? "Debits equal credits: the ledger balances"
            : "Debits do NOT balance credits — investigate before closing the period");
        checkRow.put("difference", Figures.money(difference));

        return List.of(
            new ReportSection("Balances through " + f.monthFrom(), columns, rows, totals),
            new ReportSection("Ledger check", check, List.of(checkRow), Map.of()));
    }
}
