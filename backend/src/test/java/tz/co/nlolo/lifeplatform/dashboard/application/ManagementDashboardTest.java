package tz.co.nlolo.lifeplatform.dashboard.application;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.ManagementDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFigure;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Analytics plan 5: the overview carries each figure exactly as its owner states it, and adds nothing of its own. */
class ManagementDashboardTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-12");

    private final FinanceDashboard finance = mock(FinanceDashboard.class);
    private final ClaimsDashboard claims = mock(ClaimsDashboard.class);
    private final UnderwritingDashboard underwriting = mock(UnderwritingDashboard.class);
    private final ReportingApi reporting = mock(ReportingApi.class);
    private final ManagementDashboard dashboard = new ManagementDashboard(finance, claims, underwriting, reporting);

    private static ReportResult figures(ReportFigure... figures) {
        ReportResult r = mock(ReportResult.class);
        when(r.figures()).thenReturn(List.of(figures));
        return r;
    }

    private static ReportFigure count(String key, String value) {
        return new ReportFigure(key, key, value, null, null, null, null, true, "COUNT");
    }

    /**
     * Branch views (2026-11-23): with a branch, every figure is read with that branch from the report or dashboard
     * its card opens -- premiums and new policies from their reports, not the company-wide finance dashboard -- and
     * the view names the branch and the one block that stays the company's.
     */
    @Test
    void aBranchsOverviewReadsEveryFigureWithThatBranch() {
        DashboardPeriod period = DashboardPeriod.of("2026-07", "2026-09", TODAY);
        when(reporting.branchName("ARU")).thenReturn("Arusha");
        when(reporting.policyNumbersInBranch("ARU")).thenReturn(java.util.Set.of("P-ARU-1"));
        ReportResult collected = figures(new ReportFigure("amount", "Premiums collected", "300000.00", null, null,
            null, null, true, "MONEY"));
        ReportResult activated = figures(count("policies", "3"));
        when(reporting.run(eq("premium-collections"), any())).thenReturn(collected);
        when(reporting.run(eq("new-business"), any())).thenReturn(activated);
        // Built before the stubbing that returns them: Mockito refuses a mock made inside another's when().
        ReportResult inForce = figures(count("policies", "12"), count("lapsed", "1"));
        ReportResult inProgress = figures(count("open", "2"));
        when(reporting.run(eq("policies-in-force"), any())).thenReturn(inForce);
        when(reporting.run(eq("claims-in-progress"), any())).thenReturn(inProgress);
        ClaimsDashboardView claimsView = mock(ClaimsDashboardView.class);
        when(claimsView.paid()).thenReturn(new Money("100000.00", "TZS"));
        when(claimsView.registered()).thenReturn(1L);
        when(claimsView.underAssessment()).thenReturn(0L);
        when(claimsView.reopened()).thenReturn(0L);
        when(claims.build(eq(true), any(), any(), eq("ARU"), eq("Arusha"), eq(java.util.Set.of("P-ARU-1"))))
            .thenReturn(claimsView);
        UnderwritingDashboardView cases = mock(UnderwritingDashboardView.class);
        when(cases.openCases()).thenReturn(2L);
        when(cases.casesInReview()).thenReturn(1L);
        when(underwriting.build(any(DashboardPeriod.class), any(), eq("ARU"), eq("Arusha"))).thenReturn(cases);

        ManagementDashboardView v = dashboard.build(period, TODAY, "ARU");

        assertThat(v.branch().code()).isEqualTo("ARU");
        assertThat(v.branch().name()).isEqualTo("Arusha");
        assertThat(v.branch().allBranches()).containsExactly("collections");
        assertThat(v.collected().amount()).isEqualTo("300000.00");
        assertThat(v.newPolicies()).isEqualTo(3L);
        assertThat(v.policiesInForce()).isEqualTo(12L);
        assertThat(v.claimsPaid().amount()).isEqualTo("100000.00");
        assertThat(v.openClaims()).isEqualTo(2L);
        assertThat(v.casesToDecide()).isEqualTo(3L);
        assertThat(v.claimsToDecide()).isEqualTo(1L);
        ArgumentCaptor<ReportFilter> filters = ArgumentCaptor.forClass(ReportFilter.class);
        verify(reporting, org.mockito.Mockito.atLeast(6)).run(any(), filters.capture());
        assertThat(filters.getAllValues()).allSatisfy(f -> assertThat(f.branchCode()).isEqualTo("ARU"));
    }

    @Test
    void withNoBranchTheOverviewIsTheCompanysExactlyAsBefore() {
        DashboardPeriod period = DashboardPeriod.of("2026-07", "2026-09", TODAY);
        when(finance.build(any(), any())).thenReturn(mock(FinanceDashboardView.class));
        when(claims.build(anyBoolean(), any(), any())).thenReturn(mock(ClaimsDashboardView.class));
        when(underwriting.build(any(DashboardPeriod.class), any())).thenReturn(mock(UnderwritingDashboardView.class));

        assertThat(dashboard.build(period, TODAY, null).branch()).isNull();
        verify(reporting, org.mockito.Mockito.never()).policyNumbersInBranch(any());
    }

    @Test
    void everyFigureIsItsOwnersAndLapsedIsThePeriods() {
        DashboardPeriod period = DashboardPeriod.of("2026-07", "2026-09", TODAY);
        FinanceDashboardView money = mock(FinanceDashboardView.class);
        when(money.collected()).thenReturn(new Money("5000000.00", "TZS"));
        when(money.collectedBefore()).thenReturn(new Money("4000000.00", "TZS"));
        when(money.collectedChangePercent()).thenReturn("25.0");
        when(money.newPolicies()).thenReturn(30L);
        when(money.newPoliciesBefore()).thenReturn(20L);
        when(finance.build(any(), any())).thenReturn(money);
        ClaimsDashboardView claimsView = mock(ClaimsDashboardView.class);
        when(claimsView.paid()).thenReturn(new Money("9000000.00", "TZS"));
        when(claimsView.registered()).thenReturn(2L);
        when(claimsView.underAssessment()).thenReturn(3L);
        when(claimsView.reopened()).thenReturn(1L);
        when(claims.build(anyBoolean(), any(), any())).thenReturn(claimsView);
        UnderwritingDashboardView cases = mock(UnderwritingDashboardView.class);
        when(cases.openCases()).thenReturn(4L);
        when(cases.casesInReview()).thenReturn(5L);
        when(underwriting.build(any(DashboardPeriod.class), any())).thenReturn(cases);
        ReportResult inForce = figures(count("policies", "412"), count("lapsed", "7"));
        ReportResult inProgress = figures(count("open", "9"));
        when(reporting.run(eq("policies-in-force"), any())).thenReturn(inForce);
        when(reporting.run(eq("claims-in-progress"), any())).thenReturn(inProgress);

        ManagementDashboardView v = dashboard.build(period, TODAY);

        assertThat(v.collected().amount()).isEqualTo("5000000.00");
        assertThat(v.collectedChangePercent()).isEqualTo("25.0");
        assertThat(v.newPolicies()).isEqualTo(30L);
        assertThat(v.policiesInForce()).isEqualTo(412L);
        assertThat(v.lapsed()).isEqualTo(7L);
        assertThat(v.claimsPaid().amount()).isEqualTo("9000000.00");
        assertThat(v.openClaims()).isEqualTo(9L);
        assertThat(v.casesToDecide()).isEqualTo(9L);
        assertThat(v.claimsToDecide()).isEqualTo(6L);

        // In force on the period's last day; the movement (and so "lapsed") from the day before it began.
        ArgumentCaptor<ReportFilter> filter = ArgumentCaptor.forClass(ReportFilter.class);
        verify(reporting).run(eq("policies-in-force"), filter.capture());
        assertThat(filter.getValue().asOf()).isEqualTo(LocalDate.parse("2026-09-30"));
        assertThat(filter.getValue().compareAsOf()).isEqualTo(LocalDate.parse("2026-06-30"));
    }

    /** A source that cannot be read costs its own figure, never the page -- and is never shown as zero. */
    @Test
    void aSourceThatFailsLeavesItsFiguresEmpty() {
        when(finance.build(any(), any())).thenThrow(new IllegalStateException("billing is down"));
        when(reporting.run(any(), any())).thenThrow(new IllegalStateException("reporting is down"));

        ManagementDashboardView v = dashboard.build(DashboardPeriod.of("2026-07", "2026-09", TODAY), TODAY);

        assertThat(v.collected()).isNull();
        assertThat(v.policiesInForce()).isNull();
        assertThat(v.openClaims()).isNull();
        assertThat(v.casesToDecide()).isNull();
    }
}
