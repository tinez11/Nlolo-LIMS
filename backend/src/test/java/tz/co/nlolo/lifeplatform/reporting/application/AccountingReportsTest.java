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
        return new AccountBalanceView(code, name, type, normal, parentOf(code), (short) 1, true, AccountStatus.ACTIVE, "TZS",
            new BigDecimal(ownDebit), new BigDecimal(ownCredit), new BigDecimal(ownDebit), new BigDecimal(ownCredit),
            new BigDecimal(balance));
    }

    /**
     * A summary account: no postings of its own, a rolled balance from its descendants, and the ledger refuses
     * direct postings to it. The real chart is three deep -- 1000 Assets, 1100 Cash and bank, 1140 the wallet
     * that actually takes the posting -- so every class contains two of these above every leaf.
     */
    private static AccountBalanceView summary(String code, String name, AccountType type, PostingDirection normal,
                                              String rolled) {
        return new AccountBalanceView(code, name, type, normal, parentOf(code), (short) 1, false, AccountStatus.ACTIVE, "TZS",
            BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(rolled), BigDecimal.ZERO, new BigDecimal(rolled));
    }

    /** As the chart nests them: 4110 under 4100 under 4000 -- the last non-zero digit made zero. */
    private static String parentOf(String code) {
        for (int i = code.length() - 1; i > 0; i--) {
            if (code.charAt(i) != '0') {
                return code.substring(0, i) + "0".repeat(code.length() - i);
            }
        }
        return null;
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

    /** The ledger's paged query, answering as the database would: only the postings inside the asked span. */
    private void postingsOn(String account, List<JournalEntryView> entries) {
        Mockito.when(finaccounting.accountPostings(Mockito.eq(account), Mockito.anyString(), Mockito.anyString(),
            Mockito.anyInt(), Mockito.anyInt())).thenAnswer(call -> {
                String from = call.getArgument(1);
                String to = call.getArgument(2);
                List<tz.co.nlolo.lifeplatform.finaccounting.api.AccountPostingsView.Posting> inSpan = entries.stream()
                    .flatMap(e -> e.postings().stream())
                    .filter(p -> p.period().compareTo(from) >= 0 && p.period().compareTo(to) <= 0)
                    .map(p -> new tz.co.nlolo.lifeplatform.finaccounting.api.AccountPostingsView.Posting(p.postingId(),
                        p.journalEntryId(), p.accountCode(), p.period(), Instant.now(), p.direction(), p.amount(),
                        p.currency(), p.policyNumber(), p.sourceEvent(), p.sourceRef()))
                    .toList();
                return new tz.co.nlolo.lifeplatform.finaccounting.api.AccountPostingsView(account, from, to,
                    BigDecimal.ZERO, BigDecimal.ZERO, inSpan.size(), 0, 500, inSpan);
            });
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
        // 2026-11-09: the span is asked of the ledger, never the first 2,000 entries of all time filtered here.
        Mockito.verify(finaccounting).accountPostings("1100", "2026-08", "2026-09", 0, 500);
        Mockito.verify(finaccounting, Mockito.never()).listJournalEntries(Mockito.any(), Mockito.any(), Mockito.any(),
            Mockito.any(Pageable.class));
    }

    /** 2026-11-09: opening + movement = closing, per account, beside its debits and credits. */
    @Test
    void trialBalanceShowsEachAccountsOpeningAndMovement() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(ledger("through 2026-09", List.of(
            account("1140", "Wallets", AccountType.ASSET, PostingDirection.DR, "137.00", "0.00", "137.00")),
            "137.00", "137.00"));
        Mockito.when(finaccounting.trialBalanceThrough("2026-08")).thenReturn(ledger("through 2026-08", List.of(
            account("1140", "Wallets", AccountType.ASSET, PostingDirection.DR, "130.00", "0.00", "130.00")),
            "130.00", "130.00"));

        Map<String, String> wallets = new TrialBalanceReport(finaccounting)
            .run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels).get(0).rows().get(0);

        assertEquals("130.00", wallets.get("opening"));
        assertEquals("7.00", wallets.get("movement"));
        assertEquals("137.00", wallets.get("balance"));
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

    /**
     * THE TRAP THE LEDGER'S OWN JAVADOC WARNS ABOUT, on this side of the boundary. {@code balance} is the
     * ROLLED figure, so a class holding 1000 (root), 1100 (heading) and 1140 (the leaf that was posted to)
     * carries the same 500 three times over. Listing the summary accounts and adding their balances reports
     * 1,500 in assets against one posting of 500 -- a sound ledger rendered as a wild overstatement.
     *
     * <p>Only accounts that take postings are rows, and the class total is the ROOT's rolled balance: the
     * ledger's own aggregation, which is what the hierarchy exists to provide.
     */
    @Test
    void balanceSheetDoesNotCountASummaryAccountBesideTheLeafBeneathIt() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(ledger("through 2026-09", List.of(
            summary("1000", "Assets", AccountType.ASSET, PostingDirection.DR, "500.00"),
            summary("1100", "Cash and bank", AccountType.ASSET, PostingDirection.DR, "500.00"),
            account("1140", "Mobile money wallets", AccountType.ASSET, PostingDirection.DR, "500.00", "0.00", "500.00"),
            summary("2000", "Liabilities", AccountType.LIABILITY, PostingDirection.CR, "300.00"),
            account("2100", "Payables", AccountType.LIABILITY, PostingDirection.CR, "0.00", "300.00", "300.00"),
            summary("3000", "Equity", AccountType.EQUITY, PostingDirection.CR, "200.00"),
            account("3100", "Retained earnings", AccountType.EQUITY, PostingDirection.CR, "0.00", "200.00", "200.00")),
            "500.00", "500.00"));

        List<ReportSection> sections = new BalanceSheetReport(finaccounting)
            .run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertEquals("500.00", sections.get(0).totals().get("balance"), "500, not 1500");
        // One line, the chart's group 1100 -- which opens to the leaf 1140; never the summary beside its leaf.
        assertEquals(1, sections.get(0).rows().size(), "one group, not the summaries listed with their leaves");
        assertEquals("acct:1100", sections.get(0).rows().get(0).get("_lineId"));
        assertEquals("500.00", sections.get(0).rows().get(0).get("current"));
        tz.co.nlolo.lifeplatform.reporting.api.ReportParts cash = new BalanceSheetReport(finaccounting)
            .parts(tenantId, "acct:1100", ReportFilter.month(YearMonth.of(2026, 9)), 0, labels);
        assertEquals("acct:1140", cash.rows().get(0).get("_lineId"));
        assertEquals("500.00 = 500.00", cash.check());
        assertTrue(sections.get(3).rows().get(0).get("check").contains("the sheet balances"));
    }

    @Test
    void incomeStatementDoesNotCountASummaryAccountBesideTheLeafBeneathIt() {
        Mockito.when(finaccounting.trialBalanceBetween("2026-01", "2026-09")).thenReturn(
            ledger("2026-01..2026-09", List.of(
                summary("4000", "Insurance revenue", AccountType.INCOME, PostingDirection.CR, "900.00"),
                summary("4100", "Premium revenue", AccountType.INCOME, PostingDirection.CR, "900.00"),
                account("4110", "Earned premium", AccountType.INCOME, PostingDirection.CR, "0.00", "900.00", "900.00"),
                summary("5000", "Service expenses", AccountType.EXPENSE, PostingDirection.DR, "500.00"),
                account("5100", "Claims incurred", AccountType.EXPENSE, PostingDirection.DR, "500.00", "0.00", "500.00")),
                "500.00", "900.00"));

        IncomeStatementReport report = new IncomeStatementReport(finaccounting);
        ReportFilter span = ReportFilter.months(YearMonth.of(2026, 1), YearMonth.of(2026, 9));
        List<ReportSection> sections = report.run(tenantId, span, labels);

        assertEquals("900.00", sections.get(0).rows().get(0).get("current"), "900, not 1800");
        Map<String, String> result = sections.get(sections.size() - 1).rows().get(0);
        assertEquals("400.00", result.get("amount"), "900 income less 500 expenses, each counted once");

        // The tree: revenue opens to 4100 alone, which opens to 4110 -- the summary never listed beside its leaf.
        tz.co.nlolo.lifeplatform.reporting.api.ReportParts revenue = report.parts(tenantId, "revenue", span, 0, labels);
        assertEquals(1, revenue.rows().size());
        assertEquals("acct:4100", revenue.rows().get(0).get("_lineId"));
        assertEquals("900.00 = 900.00", revenue.check());
        assertTrue(revenue.balanced());
    }

    // ---- Income statement against a compared period (2026-11-09, the reports redesign) -----------------------------

    private void twoMonths() {
        Mockito.when(finaccounting.trialBalanceBetween("2026-10", "2026-10")).thenReturn(ledger("2026-10", List.of(
            summary("4000", "Insurance revenue", AccountType.INCOME, PostingDirection.CR, "1240.50"),
            account("4100", "Premium earned", AccountType.INCOME, PostingDirection.CR, "0.00", "1102.00", "1102.00"),
            account("4200", "Fees", AccountType.INCOME, PostingDirection.CR, "0.00", "138.50", "138.50"),
            summary("5000", "Service expenses", AccountType.EXPENSE, PostingDirection.DR, "720.20"),
            account("5100", "Claims", AccountType.EXPENSE, PostingDirection.DR, "720.20", "0.00", "720.20")),
            "720.20", "1240.50"));
        Mockito.when(finaccounting.trialBalanceBetween("2026-09", "2026-09")).thenReturn(ledger("2026-09", List.of(
            summary("4000", "Insurance revenue", AccountType.INCOME, PostingDirection.CR, "1168.10"),
            account("4100", "Premium earned", AccountType.INCOME, PostingDirection.CR, "0.00", "1168.10", "1168.10"),
            summary("5000", "Service expenses", AccountType.EXPENSE, PostingDirection.DR, "667.80"),
            account("5100", "Claims", AccountType.EXPENSE, PostingDirection.DR, "667.80", "0.00", "667.80")),
            "667.80", "1168.10"));
    }

    private static ReportFilter october() {
        return ReportFilter.months(YearMonth.of(2026, 10), YearMonth.of(2026, 10))
            .withCompareWith(tz.co.nlolo.lifeplatform.reporting.api.CompareWith.PRIOR_PERIOD);
    }

    @Test
    void incomeStatementComparesWithThePriorPeriodLineByLine() {
        twoMonths();

        ReportBody body = new IncomeStatementReport(finaccounting).body(tenantId, october(), labels);

        Map<String, String> revenue = body.sections().get(0).rows().get(0);
        assertEquals("1240.50", revenue.get("current"));
        assertEquals("1168.10", revenue.get("prior"));
        assertEquals("72.40", revenue.get("change"));
        assertEquals("6.2", revenue.get("changePercent"));
        assertEquals("UP", revenue.get("_goodWhen"));
        Map<String, String> serviceResult = body.sections().get(0).rows().get(2);
        assertEquals("SUBTOTAL", serviceResult.get("_kind"));
        assertEquals("520.30", serviceResult.get("current"));
        assertEquals("compared with September 2026", body.comparisonLabel());
        assertEquals("Result", body.sections().get(1).title(), "the dashboard reads it");
    }

    @Test
    void incomeStatementLeadsWithRevenueResultAndProfitAndBridgesTheProfit() {
        twoMonths();

        ReportBody body = new IncomeStatementReport(finaccounting).body(tenantId, october(), labels);

        assertEquals(List.of("revenue", "service-result", "profit"),
            body.figures().stream().map(tz.co.nlolo.lifeplatform.reporting.api.ReportFigure::key).toList());
        assertEquals("520.30", body.figures().get(2).value());
        assertEquals("500.30", body.figures().get(2).prior());
        List<tz.co.nlolo.lifeplatform.reporting.api.ReportChart.Bar> bars = body.chart().bars();
        assertEquals("START", bars.get(0).kind());
        assertEquals("500.30", bars.get(0).value());
        assertEquals("UP", bars.get(1).kind(), "revenue rose 72.40");
        assertEquals("72.40", bars.get(1).value());
        assertEquals("DOWN", bars.get(2).kind(), "expenses rose 52.40, lowering the profit");
        assertEquals("END", bars.get(bars.size() - 1).kind());
        assertEquals("520.30", bars.get(bars.size() - 1).value());
    }

    @Test
    void comparingWithNothingLeavesOutThePriorColumnsAndTheChart() {
        twoMonths();

        ReportBody body = new IncomeStatementReport(finaccounting).body(tenantId,
            ReportFilter.months(YearMonth.of(2026, 10), YearMonth.of(2026, 10)), labels);

        assertTrue(body.sections().get(0).columns().stream().noneMatch(c -> c.key().equals("prior")));
        assertEquals(null, body.chart());
    }

    @Test
    void aStatementLineOpensToItsAccountsAndStatesTheirSum() {
        twoMonths();
        IncomeStatementReport report = new IncomeStatementReport(finaccounting);

        tz.co.nlolo.lifeplatform.reporting.api.ReportParts revenue = report.parts(tenantId, "revenue", october(), 0, labels);
        assertEquals(2, revenue.rows().size());
        assertEquals("1,102.00 + 138.50 = 1,240.50", revenue.check());
        assertTrue(revenue.balanced());

        tz.co.nlolo.lifeplatform.reporting.api.ReportParts result =
            report.parts(tenantId, "service-result", october(), 0, labels);
        assertEquals("1,240.50 − 720.20 = 520.30", result.check());
    }

    @Test
    void aLeafAccountOpensToItsPostingsAndTheirTotal() {
        twoMonths();
        Mockito.when(finaccounting.accountPostings("4200", "2026-10", "2026-10", 0, 20)).thenReturn(
            new tz.co.nlolo.lifeplatform.finaccounting.api.AccountPostingsView("4200", "2026-10", "2026-10",
                BigDecimal.ZERO, new BigDecimal("138.50"), 1, 0, 20, List.of(
                    new tz.co.nlolo.lifeplatform.finaccounting.api.AccountPostingsView.Posting(UUID.randomUUID(),
                        UUID.randomUUID(), "4200", "2026-10", java.time.Instant.now(), PostingDirection.CR,
                        new BigDecimal("138.50"), "TZS", "POL-1", "billing.PremiumCollected", "RC-000214"))));

        tz.co.nlolo.lifeplatform.reporting.api.ReportParts fees =
            new IncomeStatementReport(finaccounting).parts(tenantId, "acct:4200", october(), 0, labels);

        assertEquals(1, fees.total());
        assertTrue(fees.rows().get(0).get("line").contains("Premium collected · RC-000214 · POL-1"));
        assertEquals("138.50", fees.rows().get(0).get("current"));
        assertEquals("1 posting in 2026-10 = 138.50", fees.check());
        assertTrue(fees.balanced());
    }

    /** A trial balance lists the accounts that were posted TO; a summary account carries none of its own. */
    @Test
    void trialBalanceListsPostingAccountsNotSummaries() {
        Mockito.when(finaccounting.trialBalanceThrough("2026-09")).thenReturn(ledger("through 2026-09", List.of(
            summary("1000", "Assets", AccountType.ASSET, PostingDirection.DR, "500.00"),
            account("1140", "Mobile money wallets", AccountType.ASSET, PostingDirection.DR, "500.00", "0.00", "500.00")),
            "500.00", "500.00"));

        List<ReportSection> sections = new TrialBalanceReport(finaccounting)
            .run(tenantId, ReportFilter.month(YearMonth.of(2026, 9)), labels);

        assertEquals(1, sections.get(0).rows().size());
        assertEquals("1140", sections.get(0).rows().get(0).get("accountCode"));
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
        assertEquals("500.00", cash.get("current"));
        assertEquals("450.00", cash.get("prior"));
        assertEquals("50.00", cash.get("change"));
    }

    /** 2026-11-09: against the prior month, with total assets, liabilities and equity leading. */
    @Test
    void balanceSheetComparesWithThePriorMonthAndLeadsWithTheTotals() {
        balanceSheetLedger();
        Mockito.when(finaccounting.trialBalanceThrough("2026-08")).thenReturn(ledger("through 2026-08", List.of(
            account("1100", "Cash", AccountType.ASSET, PostingDirection.DR, "420.00", "0.00", "420.00")),
            "420.00", "420.00"));

        ReportBody body = new BalanceSheetReport(finaccounting).body(tenantId, ReportFilter.month(YearMonth.of(2026, 9))
            .withCompareWith(tz.co.nlolo.lifeplatform.reporting.api.CompareWith.PRIOR_PERIOD), labels);

        assertEquals("420.00", body.sections().get(0).rows().get(0).get("prior"));
        assertEquals("500.00", body.sections().get(0).totals().get("balance"), "the dashboard reads it");
        assertEquals("compared with the end of August 2026", body.comparisonLabel());
        assertEquals(3, body.figures().size());
        assertEquals("80.00", body.figures().get(0).change());
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
