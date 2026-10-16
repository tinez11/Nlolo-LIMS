package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportSection;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FinanceDashboardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 14);

    private final ReportingApi reporting = mock(ReportingApi.class);
    private final ManualJournalApi journals = mock(ManualJournalApi.class);
    private final BillingApi billing = mock(BillingApi.class);
    private final PaymentApi payments = mock(PaymentApi.class);
    private final RecentActivity activity = mock(RecentActivity.class);
    private final FinanceDashboard dashboard = new FinanceDashboard(reporting, journals, billing, payments, activity);

    private static MonthlyAmount m(int year, int month, String amount) {
        return new MonthlyAmount(YearMonth.of(year, month), "TZS", new BigDecimal(amount));
    }

    private void stubCountsAndReports() {
        when(journals.list(eq("SUBMITTED"), isNull(), isNull())).thenReturn(Collections.nCopies(4, null));
        when(billing.searchFieldReceipts(eq("RECONCILIATION_OVERDUE"), any()))
            .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 7));
        when(billing.searchArrears(eq(5), eq(false), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 19));
        when(payments.listAwaitingEftExecution()).thenReturn(Collections.nCopies(3, null));
        when(reporting.run(eq("balance-sheet"), any())).thenReturn(new ReportResult("balance-sheet", "", "", Instant.now(),
            List.of(new ReportSection("Assets", List.of(), List.of(), Map.of("balance", "742580120.00")),
                new ReportSection("Liabilities", List.of(), List.of(), Map.of("balance", "512340210.00")))));
        when(reporting.run(eq("income-statement"), any())).thenReturn(new ReportResult("income-statement", "", "",
            Instant.now(), List.of(new ReportSection("Result", List.of(),
                List.of(Map.of("outcome", "Profit for the period", "amount", "31204880.00")), Map.of()))));
    }

    @Test
    void readsEveryFigureFromItsOwnerAndComparesTwoCompleteMonths() {
        stubCountsAndReports();
        when(reporting.monthlyPremiumCollections(YearMonth.of(2025, 1), YearMonth.of(2026, 10))).thenReturn(List.of(
            m(2025, 10, "46200000.00"), m(2026, 8, "49100000.00"), m(2026, 9, "50204310.00"), m(2026, 10, "22600000.00")));

        FinanceDashboardView view = dashboard.build(TODAY);

        // The last COMPLETE month on 14 October is September; October is to date and is never one side of a delta.
        assertThat(view.premiumsMonth()).isEqualTo("2026-09");
        assertThat(view.premiumsCollected().amount()).isEqualTo("50204310.00");
        assertThat(view.premiumsChangePercent()).isEqualTo("2.2");
        assertThat(view.totalAssets().amount()).isEqualTo("742580120.00");
        assertThat(view.totalLiabilities().amount()).isEqualTo("512340210.00");
        assertThat(view.balanceSheetMonth()).isEqualTo("2026-10");
        assertThat(view.netResult().amount()).isEqualTo("31204880.00");
        assertThat(view.resultFrom()).isEqualTo("2026-01");
        assertThat(view.resultTo()).isEqualTo("2026-10");
        assertThat(view.journalsAwaitingApproval()).isEqualTo(4L);
        assertThat(view.receiptsOverdue()).isEqualTo(7L);
        assertThat(view.arrearsAtLapse()).isEqualTo(19L);
        assertThat(view.transfersAwaiting()).isEqualTo(3L);
        // The chart: this year to date, last year in full, one point per month that has collections.
        assertThat(view.premiumsThisYear()).extracting(FinanceDashboardView.MonthPoint::month)
            .containsExactly("2026-08", "2026-09", "2026-10");
        assertThat(view.premiumsLastYear()).extracting(FinanceDashboardView.MonthPoint::month).containsExactly("2025-10");
        assertThat(view.currentMonth()).isEqualTo("2026-10");
        assertThat(view.recentActivity()).isEmpty();
    }

    /** No delta against nothing: an absent or zero previous month is not a 100% rise. */
    @Test
    void noDeltaWhenThePreviousMonthHasNothing() {
        stubCountsAndReports();
        when(reporting.monthlyPremiumCollections(any(), any())).thenReturn(List.of(m(2026, 9, "50204310.00")));

        FinanceDashboardView view = dashboard.build(TODAY);

        assertThat(view.premiumsCollected().amount()).isEqualTo("50204310.00");
        assertThat(view.premiumsChangePercent()).isNull();
    }

    /** The working currency only. A USD receipt is not added to a TZS total. */
    @Test
    void ignoresCollectionsInAnyOtherCurrency() {
        stubCountsAndReports();
        when(reporting.monthlyPremiumCollections(any(), any())).thenReturn(List.of(
            new MonthlyAmount(YearMonth.of(2026, 9), "USD", new BigDecimal("900.00")), m(2026, 9, "50204310.00")));

        assertThat(dashboard.build(TODAY).premiumsCollected().amount()).isEqualTo("50204310.00");
    }

    @Test
    void asksTheBalanceSheetForThisMonthAndTheIncomeStatementForTheYearToDate() {
        stubCountsAndReports();
        when(reporting.monthlyPremiumCollections(any(), any())).thenReturn(List.of());

        dashboard.build(TODAY);

        Mockito.verify(reporting).run(eq("balance-sheet"),
            argThat((ReportFilter f) -> YearMonth.of(2026, 10).equals(f.monthFrom()) && f.monthTo() == null));
        Mockito.verify(reporting).run(eq("income-statement"),
            argThat((ReportFilter f) -> YearMonth.of(2026, 1).equals(f.monthFrom())
                && YearMonth.of(2026, 10).equals(f.monthTo())));
    }

    /** The finance dashboard's last dashed card: what is owed, aged, per currency. */
    @Test
    void readsReceivablesAgedPerCurrency() {
        when(billing.receivablesAging(TODAY)).thenReturn(List.of(
            new tz.co.nlolo.lifeplatform.billing.api.ReceivablesAging("TZS", List.of(
                new tz.co.nlolo.lifeplatform.billing.api.ReceivablesAging.Bucket(
                    "NOT_DUE", 1, new java.math.BigDecimal("100000.00")),
                new tz.co.nlolo.lifeplatform.billing.api.ReceivablesAging.Bucket(
                    "OVER_90", 2, new java.math.BigDecimal("400000.00"))))));

        FinanceDashboardView view = dashboard.build(TODAY);

        assertThat(view.receivables()).singleElement().satisfies(tzs -> {
            assertThat(tzs.currency()).isEqualTo("TZS");
            // An empty band keeps its zero rather than disappearing: "nothing is this overdue" is the answer.
            assertThat(tzs.buckets())
                .extracting(b -> b.name() + " " + b.cases() + " " + b.outstanding().amount())
                .containsExactly("NOT_DUE 1 100000.00", "OVER_90 2 400000.00");
            assertThat(tzs.buckets()).allSatisfy(b ->
                assertThat(b.outstanding().currencyCode()).isEqualTo("TZS"));
        });
    }

    /** Billing being down costs the receivables card and nothing else. */
    @Test
    void aFailedReceivablesReadLeavesTheRestStanding() {
        when(billing.receivablesAging(TODAY)).thenThrow(new IllegalStateException("billing is down"));

        FinanceDashboardView view = dashboard.build(TODAY);

        assertThat(view.receivables()).isEmpty();
        // The rest of the page was still built: the journals count read fine and the month is set. Asserted on
        // figures that do NOT come from the reporting mock, which this test deliberately leaves unstubbed.
        assertThat(view.journalsAwaitingApproval()).isZero();
        assertThat(view.currentMonth()).isEqualTo("2026-10");
    }
}
