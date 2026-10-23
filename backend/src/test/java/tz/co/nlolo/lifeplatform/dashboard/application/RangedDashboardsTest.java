package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import tz.co.nlolo.lifeplatform.billing.api.BillingApi;
import tz.co.nlolo.lifeplatform.billing.api.ReceivablesAging;
import tz.co.nlolo.lifeplatform.claims.api.ClaimMonth;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.claims.api.SettlementByType;
import tz.co.nlolo.lifeplatform.claims.api.SettlementMonth;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import tz.co.nlolo.lifeplatform.finaccounting.api.ManualJournalApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.payment.api.PaymentApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.reporting.api.CategoryAmount;
import tz.co.nlolo.lifeplatform.reporting.api.CategoryCount;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyAmount;
import tz.co.nlolo.lifeplatform.reporting.api.MonthlyCount;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionMonth;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionTurnaround;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ranged figures every dashboard gained on 2026-10-23: the console sends two months, the server does everything
 * after that -- which months a series has, what they are compared with, every total, share and average.
 */
class RangedDashboardsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    private static final DashboardPeriod Q3 = DashboardPeriod.of("2026-07", "2026-09", TODAY);

    private static YearMonth ym(int y, int m) {
        return YearMonth.of(y, m);
    }

    // ---- the range itself ----

    @Test
    void defaultsToTheYearToDateAndComparesTheSameMonthsAYearEarlier() {
        DashboardPeriod p = DashboardPeriod.of(null, null, TODAY);
        assertThat(p.from()).isEqualTo(ym(2026, 1));
        assertThat(p.to()).isEqualTo(ym(2026, 10));
        assertThat(p.months()).hasSize(10);
        assertThat(p.toView().compareFrom()).isEqualTo("2025-01");
        assertThat(p.toView().compareTo()).isEqualTo("2025-10");
        assertThat(p.toView().currentMonth()).isEqualTo("2026-10");
    }

    @Test
    void refusesARangeThatRunsBackwardsEndsInTheFutureIsTooWideOrIsNotAMonth() {
        assertThatThrownBy(() -> DashboardPeriod.of("2026-09", "2026-07", TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DashboardPeriod.of(null, "2026-11", TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DashboardPeriod.of("2023-01", null, TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DashboardPeriod.of("2026-13", null, TODAY)).isInstanceOf(IllegalArgumentException.class);
        assertThat(DashboardPeriod.of("2023-11", "2026-10", TODAY).months()).hasSize(36);
    }

    @Test
    void sharesAveragesAndChangesAreWorkedOutHereToOneDecimal() {
        assertThat(DashboardPeriod.percentOf(new BigDecimal("1"), new BigDecimal("3"))).isEqualTo("33.3");
        assertThat(DashboardPeriod.percentOf(BigDecimal.ONE, BigDecimal.ZERO)).isNull();
        assertThat(DashboardPeriod.changePercent(new BigDecimal("90"), new BigDecimal("100"))).isEqualTo("-10.0");
        assertThat(DashboardPeriod.average(new BigDecimal("7.04"), 2)).isEqualTo("3.5");
        assertThat(DashboardPeriod.average(BigDecimal.TEN, 0)).isNull();
    }

    // ---- finance ----

    @Test
    void financeSumsThePeriodFillsEveryMonthAndSharesTheProductLines() {
        ReportingApi reporting = mock(ReportingApi.class);
        BillingApi billing = mock(BillingApi.class);
        when(reporting.monthlyPremiumCollections(any(), any())).thenReturn(List.of(
            new MonthlyAmount(ym(2025, 7), "TZS", new BigDecimal("100.00")),
            new MonthlyAmount(ym(2026, 7), "TZS", new BigDecimal("120.00")),
            new MonthlyAmount(ym(2026, 9), "TZS", new BigDecimal("30.00")),
            new MonthlyAmount(ym(2026, 9), "USD", new BigDecimal("999.00"))));
        when(reporting.premiumCollectionsByCategory(ym(2026, 7), ym(2026, 9))).thenReturn(List.of(
            new CategoryAmount("TERM_LIFE", "TZS", new BigDecimal("100.00")),
            new CategoryAmount("FUNERAL", "TZS", new BigDecimal("50.00")),
            new CategoryAmount("TERM_LIFE", "USD", new BigDecimal("999.00"))));
        when(reporting.policiesIssuedByMonth(ym(2025, 7), ym(2026, 9), null)).thenReturn(List.of(
            new MonthlyCount(ym(2025, 8), 4), new MonthlyCount(ym(2026, 8), 5)));
        when(billing.receivablesAging(TODAY)).thenReturn(List.of(new ReceivablesAging("TZS", List.of(
            new ReceivablesAging.Bucket("NOT_DUE", 1, new BigDecimal("10.00")),
            new ReceivablesAging.Bucket("OVER_90", 1, new BigDecimal("5.50"))))));
        FinanceDashboard finance = new FinanceDashboard(reporting, mock(ManualJournalApi.class), billing,
            mock(PaymentApi.class), mock(RecentActivity.class));

        FinanceDashboardView view = finance.build(Q3, TODAY);

        assertThat(view.period().from()).isEqualTo("2026-07");
        assertThat(view.collected().amount()).isEqualTo("150.00");
        assertThat(view.collectedBefore().amount()).isEqualTo("100.00");
        assertThat(view.collectedChangePercent()).isEqualTo("50.0");
        // August had nothing: present as zero, never dropped, and the USD collection is in no TZS figure.
        assertThat(view.collectedSeries()).extracting(p -> p.month() + " " + p.amount().amount())
            .containsExactly("2026-07 120.00", "2026-08 0.00", "2026-09 30.00");
        assertThat(view.collectedCompareSeries()).extracting(p -> p.month()).containsExactly("2025-07", "2025-08", "2025-09");
        assertThat(view.collectedByProductLine()).extracting(s -> s.key() + " " + s.percent())
            .containsExactly("TERM_LIFE 66.7", "FUNERAL 33.3");
        assertThat(view.newPolicies()).isEqualTo(5L);
        assertThat(view.newPoliciesBefore()).isEqualTo(4L);
        assertThat(view.newPoliciesChangePercent()).isEqualTo("25.0");
        assertThat(view.outstanding().amount()).isEqualTo("15.50");
    }

    // ---- underwriting ----

    @Test
    void underwritingGroupsOutcomesAndAveragesTheDaysExactly() {
        UnderwritingApi underwriting = mock(UnderwritingApi.class);
        when(underwriting.listCases(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(underwriting.listCases(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(underwriting.listCasesAwaitingIssue(any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(underwriting.decisionsByMonth(ym(2026, 7), ym(2026, 9))).thenReturn(List.of(
            new DecisionMonth(ym(2026, 7), DecisionOutcome.ACCEPT, 6, null),
            new DecisionMonth(ym(2026, 7), DecisionOutcome.LOADED, 1, null),
            new DecisionMonth(ym(2026, 9), DecisionOutcome.DECLINED, 2, null),
            new DecisionMonth(ym(2026, 9), DecisionOutcome.POSTPONED, 1, null)));
        when(underwriting.decisionsByMonth(ym(2025, 7), ym(2025, 9))).thenReturn(List.of(
            new DecisionMonth(ym(2025, 8), DecisionOutcome.ACCEPT, 8, null)));
        when(underwriting.decisionTurnaroundByMonth(ym(2026, 7), ym(2026, 9))).thenReturn(List.of(
            new DecisionTurnaround(ym(2026, 7), 7, new BigDecimal("21.04")),
            new DecisionTurnaround(ym(2026, 9), 3, new BigDecimal("15.10"))));
        UnderwritingDashboard dashboard = new UnderwritingDashboard(underwriting, mock(RecentActivity.class));

        UnderwritingDashboardView view = dashboard.build(Q3, TODAY);

        assertThat(view.decided()).isEqualTo(10L);
        assertThat(view.decidedBefore()).isEqualTo(8L);
        assertThat(view.decidedChangePercent()).isEqualTo("25.0");
        assertThat(view.acceptanceRate()).isEqualTo("70.0");
        // (21.04 + 15.10) / 10 cases, not the average of two monthly averages.
        assertThat(view.averageDays()).isEqualTo("3.6");
        assertThat(view.outcomeTotals()).extracting(s -> s.key() + " " + s.count() + " " + s.percent())
            .containsExactly("ACCEPT 6 60.0", "DECLINED 2 20.0", "LOADED 1 10.0", "POSTPONED 1 10.0");
        assertThat(view.outcomeSeries()).extracting(p -> p.month() + " " + p.accepted() + "/" + p.postponed() + "/" + p.declined())
            .containsExactly("2026-07 7/0/0", "2026-08 0/0/0", "2026-09 0/1/2");
        assertThat(view.turnaroundSeries()).extracting(d -> d.month() + " " + d.averageDays())
            .containsExactly("2026-07 3.0", "2026-08 null", "2026-09 5.0");
    }

    // ---- claims ----

    @Test
    void claimsPaysInTheWorkingCurrencyByTypeAndAveragesTheDaysToSettle() {
        ClaimsApi claims = mock(ClaimsApi.class);
        when(claims.searchClaims(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
        when(claims.settlementsByMonth(ym(2025, 7), ym(2026, 9))).thenReturn(List.of(
            new SettlementMonth(ym(2025, 9), "TZS", 1, new BigDecimal("50.00")),
            new SettlementMonth(ym(2026, 8), "TZS", 2, new BigDecimal("75.00")),
            new SettlementMonth(ym(2026, 8), "USD", 1, new BigDecimal("9.00"))));
        when(claims.settlementsByMonth(ym(2025, 10), ym(2026, 9))).thenReturn(List.of());
        when(claims.settlementsByType(ym(2026, 7), ym(2026, 9))).thenReturn(List.of(
            new SettlementByType("DEATH", "TZS", 1, new BigDecimal("60.00")),
            new SettlementByType("MATURITY", "TZS", 1, new BigDecimal("15.00"))));
        when(claims.settlementTurnaroundByMonth(ym(2026, 7), ym(2026, 9))).thenReturn(List.of(
            new ClaimMonth(ym(2026, 8), 3, new BigDecimal("30.00"))));
        when(claims.registrationsByMonth(ym(2026, 7), ym(2026, 9))).thenReturn(List.of(
            new ClaimMonth(ym(2026, 7), 4, null), new ClaimMonth(ym(2026, 9), 1, null)));
        ClaimsDashboard dashboard = new ClaimsDashboard(claims, mock(RecentActivity.class));

        ClaimsDashboardView view = dashboard.build(true, Q3, TODAY);

        assertThat(view.paid().amount()).isEqualTo("75.00");
        assertThat(view.paidBefore().amount()).isEqualTo("50.00");
        assertThat(view.paidChangePercent()).isEqualTo("50.0");
        assertThat(view.paidSeries()).extracting(p -> p.month() + " " + p.amount().amount())
            .containsExactly("2026-07 0.00", "2026-08 75.00", "2026-09 0.00");
        assertThat(view.paidByType()).extracting(s -> s.key() + " " + s.percent()).containsExactly("DEATH 80.0", "MATURITY 20.0");
        assertThat(view.settledInPeriod()).isEqualTo(3L);
        assertThat(view.registeredInPeriod()).isEqualTo(5L);
        assertThat(view.registeredSeries()).extracting(c -> c.count()).containsExactly(4L, 0L, 1L);
        assertThat(view.averageDaysToSettle()).isEqualTo("10.0");
    }

    // ---- agent ----

    @Test
    void anAgentSeesCommissionPaidAgainstPendingAndOnlyTheirTeamsSales() {
        UUID party = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        PartyApi parties = mock(PartyApi.class);
        PolicyApi policies = mock(PolicyApi.class);
        DistributionApi distribution = mock(DistributionApi.class);
        UnderwritingApi underwriting = mock(UnderwritingApi.class);
        ReportingApi reporting = mock(ReportingApi.class);
        when(parties.searchParties(any(), anyString(), isNull(), isNull(), any())).thenReturn(new PageImpl<>(List.of()));
        when(distribution.resolveAgentTeam(party)).thenReturn(List.of(agentId));
        when(distribution.agentIdForParty(party)).thenReturn(Optional.of(agentId));
        when(distribution.listStatements(eq(agentId), anyString())).thenReturn(List.of());
        when(distribution.listStatements(agentId, "2026-07")).thenReturn(List.of(new CommissionStatementView(UUID.randomUUID(),
            agentId, "2026-07", new BigDecimal("1000.00"), "TZS", StatementStatus.PAID, Instant.now(), Instant.now())));
        when(distribution.listStatements(agentId, "2026-09")).thenReturn(List.of(new CommissionStatementView(UUID.randomUUID(),
            agentId, "2026-09", new BigDecimal("400.00"), "TZS", StatementStatus.CLOSED, Instant.now(), null)));
        when(reporting.policiesIssuedByMonth(ym(2025, 7), ym(2026, 9), Set.of(agentId))).thenReturn(List.of(
            new MonthlyCount(ym(2026, 7), 2), new MonthlyCount(ym(2026, 9), 1), new MonthlyCount(ym(2025, 9), 1)));
        when(reporting.policiesInForceByCategory(TODAY, Set.of(agentId))).thenReturn(List.of(
            new CategoryCount("TERM_LIFE", 3), new CategoryCount("FUNERAL", 1)));
        AgentDashboard dashboard = new AgentDashboard(parties, policies, distribution, underwriting, reporting);

        AgentDashboardView view = dashboard.build("agent-subject", party, Q3, TODAY);

        assertThat(view.commissionInPeriod().amount()).isEqualTo("1400.00");
        assertThat(view.commissionSeries()).extracting(c -> c.month() + " " + c.paid().amount() + "/" + c.pending().amount())
            .containsExactly("2026-07 1000.00/0.00", "2026-08 0.00/0.00", "2026-09 0.00/400.00");
        assertThat(view.sold()).isEqualTo(3L);
        assertThat(view.soldBefore()).isEqualTo(1L);
        assertThat(view.soldChangePercent()).isEqualTo("200.0");
        assertThat(view.bookByProduct()).extracting(s -> s.key() + " " + s.percent()).containsExactly("TERM_LIFE 75.0", "FUNERAL 25.0");
    }
}
