package tz.co.nlolo.lifeplatform.reporting.application;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountBalanceView;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.AccountType;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingApi;
import tz.co.nlolo.lifeplatform.finaccounting.api.GlPostingView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalEntryView;
import tz.co.nlolo.lifeplatform.finaccounting.api.JournalSource;
import tz.co.nlolo.lifeplatform.finaccounting.api.PostingDirection;
import tz.co.nlolo.lifeplatform.finaccounting.api.TrialBalanceView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportParameterException;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four accounting reports, against a mocked ledger. The ledger's own arithmetic is pinned by
 * FinaccountingApiIntegrationTest against a real Postgres; what these pin is the reports' reading of it --
 * which entry point each calls, what it leaves out, and the checks it states.
 */
class AccountingReportsTest {

    private final FinaccountingApi finaccounting = Mockito.mock(FinaccountingApi.class);
    private final Labels labels = Mockito.mock(Labels.class);
    private final UUID tenantId = UUID.randomUUID();

    private static AccountBalanceView account(String code, String name, AccountType type, PostingDirection normal,
                                              String ownDebit, String ownCredit, String balance) {
        return new AccountBalanceView(code, name, type, normal, null, (short) 1, true, AccountStatus.ACTIVE, "TZS",
            new BigDecimal(ownDebit), new BigDecimal(ownCredit), new BigDecimal(ownDebit), new BigDecimal(ownCredit),
            new BigDecimal(balance));
    }

    private static TrialBalanceView ledger(String label, List<AccountBalanceView> accounts,
                                           String totalDebit, String totalCredit) {
        BigDecimal dr = new BigDecimal(totalDebit);
        BigDecimal cr = new BigDecimal(totalCredit);
        return new TrialBalanceView(label, accounts, dr, cr, dr.compareTo(cr) == 0);
    }

    // ---- Trial balance -----------------------------------------------------------------------

    @Test
    void trialBalanceListsEveryPostedAccountAndStatesThatTheLedgerBalances() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(ledger("through 2026-09", List.of(
            account("1100", "Premium receivables", AccountType.ASSET, PostingDirection.DR, "130.00", "0.00", "130.00"),
            account("4100", "Insurance revenue", AccountType.INCOME, PostingDirection.CR, "0.00", "130.00", "130.00"),
            account("1200", "Never used", AccountType.ASSET, PostingDirection.DR, "0.00", "0.00", "0.00")),
            "130.00", "130.00"));

        List<ReportSection> sections = new TrialBalanceReport(finaccounting)
            .run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels);

        ReportSection balances = sections.get(0);
        assertEquals(2, balances.rows().size(), "an account with no movement is not a row");
        assertEquals("1100", balances.rows().get(0).get("accountCode"));
        assertEquals("130.00", balances.totals().get("debit"));
        assertEquals("130.00", balances.totals().get("credit"));
        assertTrue(sections.get(1).rows().get(0).get("status").contains("the ledger balances"));
    }

    @Test
    void trialBalanceSaysSoLoudlyWhenTheLedgerDoesNotBalance() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(ledger("through 2026-09", List.of(
            account("1100", "Premium receivables", AccountType.ASSET, PostingDirection.DR, "130.00", "0.00", "130.00")),
            "130.00", "129.00"));

        List<ReportSection> sections = new TrialBalanceReport(finaccounting)
            .run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertTrue(sections.get(1).rows().get(0).get("status").contains("do NOT balance"));
        assertEquals("1.00", sections.get(1).rows().get(0).get("difference"));
    }

    /** A trial balance is the position AT a date, not one month's movement in isolation. */
    @Test
    void trialBalanceReadsCumulativelyThroughTheMonth() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09"))
            .thenReturn(ledger("through 2026-09", List.of(), "0.00", "0.00"));

        new TrialBalanceReport(finaccounting).run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels);

        Mockito.verify(finaccounting).trialBalanceThrough("2026-09");
        Mockito.verify(finaccounting, Mockito.never()).trialBalance(Mockito.anyString());
    }

    // ---- General ledger ----------------------------------------------------------------------

    private static JournalEntryView entry(String period, String account, PostingDirection direction, String amount) {
        UUID entryId = UUID.randomUUID();
        GlPostingView posting = new GlPostingView(UUID.randomUUID(), entryId, account, direction,
            new BigDecimal(amount), "TZS", period, "POL-1", "billing.PremiumCollected", "ref-1", null);
        return new JournalEntryView(entryId, "billing.PremiumCollected", "ref-1", period, "POL-1",
            Instant.parse(period + "-15T00:00:00Z"), List.of(posting), JournalSource.EVENT, 1, "v1");
    }

    private void openingBalanceOf(String period, String amount) {
        Mockito.when(finaccounting.trialBalanceThrough(period)).thenReturn(ledger("through " + period, List.of(
            account("1100", "Premium receivables", AccountType.ASSET, PostingDirection.DR, amount, "0.00", amount)),
            amount, "0.00"));
    }

    private void postingsOn(String account, List<JournalEntryView> entries) {
        Page<JournalEntryView> page = new PageImpl<>(entries, Pageable.ofSize(2000), entries.size());
        Mockito.when(finaccounting.listJournalEntries(Mockito.isNull(), Mockito.isNull(), Mockito.eq(account),
            Mockito.any(Pageable.class))).thenReturn(page);
    }

    @Test
    void generalLedgerOpensWithThePositionBeforeTheRangeAndRunsABalanceForward() {
        openingBalanceOf("2026-07", "100.00");
        postingsOn("1100", List.of(
            entry("2026-08", "1100", PostingDirection.DR, "30.00"),
            entry("2026-09", "1100", PostingDirection.CR, "7.00")));

        List<ReportSection> sections = new GeneralLedgerReport(finaccounting).run(tenantId,
            ReportFilter.months(YearMonth.of(2026, 8), YearMonth.of(2026, 9)).withAccountCode("1100"), labels);

        List<Map<String, String>> rows = sections.get(0).rows();
        assertEquals("Opening balance", rows.get(0).get("description"));
        assertEquals("100.00", rows.get(0).get("balance"));
        assertEquals("130.00", rows.get(1).get("balance"), "a debit on a debit-normal account adds");
        assertEquals("123.00", rows.get(2).get("balance"), "a credit on a debit-normal account subtracts");
        assertEquals("123.00", sections.get(0).totals().get("balance"));
    }

    @Test
    void generalLedgerLeavesOutEntriesOutsideTheRange() {
        openingBalanceOf("2026-07", "0.00");
        postingsOn("1100", List.of(
            entry("2026-06", "1100", PostingDirection.DR, "5.00"),
            entry("2026-08", "1100", PostingDirection.DR, "30.00"),
            entry("2026-11", "1100", PostingDirection.DR, "9.00")));

        List<ReportSection> sections = new GeneralLedgerReport(finaccounting).run(tenantId,
            ReportFilter.months(YearMonth.of(2026, 8), YearMonth.of(2026, 9)).withAccountCode("1100"), labels);

        assertEquals(2, sections.get(0).rows().size(), "the opening balance plus the one entry inside the range");
    }

    @Test
    void generalLedgerRefusesToRunWithoutAnAccount() {
        ReportParameterException e = assertThrows(ReportParameterException.class,
            () -> new GeneralLedgerReport(finaccounting).run(tenantId,
                ReportFilter.months(YearMonth.of(2026, 8), YearMonth.of(2026, 9)), labels));
        assertTrue(e.getMessage().contains("accountCode"));
    }

    // ---- Balance sheet -----------------------------------------------------------------------

    private void balanceSheetLedger() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(ledger("through 2026-09", List.of(
            account("1100", "Cash", AccountType.ASSET, PostingDirection.DR, "500.00", "0.00", "500.00"),
            account("2100", "Payables", AccountType.LIABILITY, PostingDirection.CR, "0.00", "300.00", "300.00"),
            account("3100", "Retained earnings", AccountType.EQUITY, PostingDirection.CR, "0.00", "200.00", "200.00"),
            account("4100", "Revenue", AccountType.INCOME, PostingDirection.CR, "0.00", "900.00", "900.00")),
            "500.00", "1400.00"));
    }

    @Test
    void balanceSheetSectionsTheChartAndStatesTheAccountingEquation() {
        balanceSheetLedger();

        List<ReportSection> sections = new BalanceSheetReport(finaccounting)
            .run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertEquals("Assets", sections.get(0).title());
        assertEquals("500.00", sections.get(0).totals().get("balance"));
        assertEquals("Liabilities", sections.get(1).title());
        assertEquals("Equity", sections.get(2).title());
        assertEquals("Accounting equation", sections.get(3).title());
        assertTrue(sections.get(3).rows().get(0).get("check").contains("the sheet balances"),
            "500 assets = 300 liabilities + 200 equity");
    }

    @Test
    void balanceSheetNeverShowsAnIncomeAccount() {
        balanceSheetLedger();

        List<ReportSection> sections = new BalanceSheetReport(finaccounting)
            .run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertTrue(sections.stream().flatMap(s -> s.rows().stream())
            .noneMatch(r -> "4100".equals(r.get("accountCode"))));
    }

    @Test
    void balanceSheetSaysSoWhenItDoesNotBalance() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(ledger("through 2026-09", List.of(
            account("1100", "Cash", AccountType.ASSET, PostingDirection.DR, "500.00", "0.00", "500.00"),
            account("2100", "Payables", AccountType.LIABILITY, PostingDirection.CR, "0.00", "300.00", "300.00")),
            "500.00", "300.00"));

        List<ReportSection> sections = new BalanceSheetReport(finaccounting)
            .run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertTrue(sections.get(3).rows().get(0).get("check").contains("do NOT balance"));
        assertEquals("200.00", sections.get(3).rows().get(0).get("difference"));
    }

    /** The comparison column reads the month the compare date falls in, and shows the movement between them. */
    @Test
    void balanceSheetComparesAgainstAnEarlierMonth() {
        balanceSheetLedger();
        Mockito.when(finaccounting.trialBalanceThrough("2026-06")).thenReturn(ledger("through 2026-06", List.of(
            account("1100", "Cash", AccountType.ASSET, PostingDirection.DR, "450.00", "0.00", "450.00")),
            "450.00", "450.00"));

        List<ReportSection> sections = new BalanceSheetReport(finaccounting).run(tenantId,
            ReportFilter.month(YearMonth.of(2026, 9)).withCompareAsOf(LocalDate.of(2026, 6, 30)), labels);

        Map<String, String> cash = sections.get(0).rows().get(0);
        assertEquals("500.00", cash.get("balance"));
        assertEquals("450.00", cash.get("comparison"));
        assertEquals("50.00", cash.get("movement"));
    }

    // ---- Income statement --------------------------------------------------------------------

    private void incomeLedger() {
        Mockito.when(finaccounting.trialBalanceBetween("2026-01", "2026-09")).thenReturn(
            ledger("2026-01..2026-09", List.of(
                account("4100", "Revenue", AccountType.INCOME, PostingDirection.CR, "0.00", "900.00", "900.00"),
                account("5100", "Claims incurred", AccountType.EXPENSE, PostingDirection.DR, "400.00", "0.00", "400.00"),
                account("8100", "Admin", AccountType.EXPENSE, PostingDirection.DR, "100.00", "0.00", "100.00"),
                account("1100", "Cash", AccountType.ASSET, PostingDirection.DR, "500.00", "0.00", "500.00")),
                "1000.00", "900.00"));
    }

    /** An income statement reports a SPAN. Reading a cumulative position here would report inception to date. */
    @Test
    void incomeStatementReadsTheMovementAcrossTheRange() {
        incomeLedger();

        new IncomeStatementReport(finaccounting).run(tenantId,
            ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9)), labels);

        Mockito.verify(finaccounting).trialBalanceBetween("2026-01", "2026-09");
        Mockito.verify(finaccounting, Mockito.never()).trialBalanceThrough(Mockito.anyString());
    }

    @Test
    void incomeStatementStatesTheResultAsIncomeLessExpenses() {
        incomeLedger();

        List<ReportSection> sections = new IncomeStatementReport(finaccounting).run(tenantId,
            ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9)), labels);

        ReportSection result = sections.get(sections.size() - 1);
        assertEquals("Result", result.title());
        assertEquals("400.00", result.rows().get(0).get("amount"), "900 income less 500 expenses");
        assertTrue(result.rows().get(0).get("outcome").contains("Profit"));
    }

    @Test
    void incomeStatementNeverShowsABalanceSheetAccount() {
        incomeLedger();

        List<ReportSection> sections = new IncomeStatementReport(finaccounting).run(tenantId,
            ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9)), labels);

        assertTrue(sections.stream().flatMap(s -> s.rows().stream())
            .noneMatch(r -> "1100".equals(r.get("accountCode"))));
    }

    @Test
    void incomeStatementReportsALossWhenExpensesExceedIncome() {
        Mockito.when(finaccounting.trialBalanceBetween("2026-01", "2026-09")).thenReturn(
            ledger("2026-01..2026-09", List.of(
                account("4100", "Revenue", AccountType.INCOME, PostingDirection.CR, "0.00", "100.00", "100.00"),
                account("5100", "Claims incurred", AccountType.EXPENSE, PostingDirection.DR, "250.00", "0.00", "250.00")),
                "250.00", "100.00"));

        List<ReportSection> sections = new IncomeStatementReport(finaccounting).run(tenantId,
            ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9)), labels);

        Map<String, String> result = sections.get(sections.size() - 1).rows().get(0);
        assertTrue(result.get("outcome").contains("Loss"));
        assertEquals("150.00", result.get("amount"), "a loss is shown as a positive magnitude, named as a loss");
    }

    /** 7000 is the finance and investment RESULT and adds to the result; 6000 is reinsurance and subtracts. */
    @Test
    void incomeStatementTreatsTheFinanceResultAsIncomeAndReinsuranceAsExpense() {
        Mockito.when(finaccounting.trialBalanceBetween("2026-01", "2026-09")).thenReturn(
            ledger("2026-01..2026-09", List.of(
                account("6100", "Reinsurance result", AccountType.EXPENSE, PostingDirection.DR, "40.00", "0.00", "40.00"),
                account("7100", "Investment income", AccountType.INCOME, PostingDirection.CR, "0.00", "90.00", "90.00")),
                "40.00", "90.00"));

        List<ReportSection> sections = new IncomeStatementReport(finaccounting).run(tenantId,
            ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9)), labels);

        Map<String, String> result = sections.get(sections.size() - 1).rows().get(0);
        assertTrue(result.get("outcome").contains("Profit"));
        assertEquals("50.00", result.get("amount"), "90 finance income less 40 reinsurance expense");
    }
}
