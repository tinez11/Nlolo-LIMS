package tz.co.nlolo.lifeplatform.reporting.application;

import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.DateFilterKind;
import tz.co.nlolo.lifeplatform.reporting.api.ReportCategory;
import tz.co.nlolo.lifeplatform.reporting.api.ReportColumn;
import tz.co.nlolo.lifeplatform.reporting.api.ReportDefinitionView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.domain.Figures;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.MONEY;
import static tz.co.nlolo.lifeplatform.reporting.api.ColumnType.TEXT;

/**
 * Every posted account's month: where it opened, what was debited and credited to it in the month, and where it
 * closed -- and the one assertion a ledger must always be able to make.
 *
 * <p>2026-11-09, the user: "not user friendly at all". The month and the position no longer mix: debits and credits
 * are the MONTH's (they were inception-to-date beside a month's opening and movement), the account's code and name
 * read as one column, and Type and Currency -- the code says the one, and the book has one currency -- are gone. Each
 * row is opening + debits - credits = closing in the account's normal direction.
 *
 * <p>The ledger check is the ledger's own: cumulative debits against credits through the month, never a sum made here.
 */
@Component
public class TrialBalanceReport implements Report {

    static final ReportDefinitionView DEFINITION = new ReportDefinitionView("trial-balance", "Trial balance",
        "Every posted account's opening balance, the month's debits and credits, and its closing balance; and whether"
            + " the ledger balances.",
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
        Map<String, BigDecimal> opening = balances(finaccounting.trialBalanceThrough(f.monthFrom().minusMonths(1).toString()));
        TrialBalanceView month = finaccounting.trialBalance(f.monthFrom().toString());
        Map<String, AccountBalanceView> moved = new HashMap<>();
        if (month != null) {
            for (AccountBalanceView a : month.accounts()) moved.put(a.accountCode(), a);
        }

        List<ReportColumn> columns = List.of(new ReportColumn("account", "Account", TEXT),
            new ReportColumn("opening", "Opening", MONEY), new ReportColumn("debit", "Debits", MONEY),
            new ReportColumn("credit", "Credits", MONEY), new ReportColumn("balance", "Closing", MONEY));

        // The accounts that were posted TO. A summary account carries no postings of its own, so it would be a
        // row of two zeroes beside a balance it inherited -- and the roll-up belongs to the statements, not here.
        List<Map<String, String>> rows = ledger.accounts().stream()
            .filter(a -> a.postingAllowed() && LedgerAccounts.posted(a)).map(a -> {
                AccountBalanceView m = moved.get(a.accountCode());
                Map<String, String> row = new LinkedHashMap<>();
                row.put("account", a.accountCode() + " " + a.name());
                row.put("accountCode", a.accountCode());
                row.put("opening", Figures.money(opening.getOrDefault(a.accountCode(), BigDecimal.ZERO)));
                row.put("debit", Figures.money(m == null ? BigDecimal.ZERO : m.ownDebit()));
                row.put("credit", Figures.money(m == null ? BigDecimal.ZERO : m.ownCredit()));
                row.put("balance", Figures.money(a.balance()));
                return row;
            }).toList();

        Map<String, String> totals = new LinkedHashMap<>();
        totals.put("account", "Total");
        totals.put("opening", "");
        totals.put("debit", Figures.money(month == null ? BigDecimal.ZERO : month.totalDebit()));
        totals.put("credit", Figures.money(month == null ? BigDecimal.ZERO : month.totalCredit()));
        totals.put("balance", "");

        List<ReportColumn> check = List.of(new ReportColumn("status", "Check", TEXT),
            new ReportColumn("difference", "Difference", MONEY));
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

    /** The month's debits and credits, and the ledger's difference -- zero, or the first thing anyone must look at. */
    @Override
    public ReportBody body(UUID tenantId, ReportFilter f, Labels labels) {
        List<ReportSection> sections = run(tenantId, f, labels);
        Map<String, String> totals = sections.get(0).totals();
        String difference = sections.get(1).rows().get(0).get("difference");
        List<ReportFigure> figures = List.of(
            new ReportFigure("debit", "Debits in the month", totals.get("debit"), null, null, null, null, true),
            new ReportFigure("credit", "Credits in the month", totals.get("credit"), null, null, null, null, true),
            new ReportFigure("difference", "Out of balance", difference, null, null, null, null, true));
        return new ReportBody(sections, figures, null, null);
    }

    private static Map<String, BigDecimal> balances(TrialBalanceView view) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (view != null) {
            for (AccountBalanceView a : view.accounts()) out.put(a.accountCode(), a.balance());
        }
        return out;
    }
}
