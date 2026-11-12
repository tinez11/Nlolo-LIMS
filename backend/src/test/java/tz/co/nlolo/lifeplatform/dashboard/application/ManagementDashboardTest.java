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
