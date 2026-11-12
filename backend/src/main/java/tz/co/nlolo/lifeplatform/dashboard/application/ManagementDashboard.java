package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.ManagementDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.reporting.api.ReportFilter;
import tz.co.nlolo.lifeplatform.reporting.api.ReportResult;
import tz.co.nlolo.lifeplatform.reporting.api.ReportingApi;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The management overview (2026-11-12, analytics plan 5, D2). Works out nothing of its own: each figure is read from
 * the dashboard or report that already owns it, so the overview can never disagree with the screen its card opens.
 *
 * <ul>
 *   <li>Premiums collected, new policies, the collection figures -- the finance dashboard's.</li>
 *   <li>Policies in force and lapsed -- the Policies in force report, on the period's last day, its movement from the
 *       day before the period began.</li>
 *   <li>Claims paid -- the claims dashboard's; open claims -- Claims in progress, on the period's last day.</li>
 *   <li>Waiting for a decision -- the underwriting and claims dashboards' live counts.</li>
 * </ul>
 */
@Service
public class ManagementDashboard {

    private final FinanceDashboard finance;
    private final ClaimsDashboard claims;
    private final UnderwritingDashboard underwriting;
    private final ReportingApi reporting;

    public ManagementDashboard(FinanceDashboard finance, ClaimsDashboard claims, UnderwritingDashboard underwriting,
                               ReportingApi reporting) {
        this.finance = finance;
        this.claims = claims;
        this.underwriting = underwriting;
        this.reporting = reporting;
    }

    public ManagementDashboardView build(DashboardPeriod period, LocalDate today) {
        LocalDate end = period.to().atEndOfMonth().isAfter(today) ? today : period.to().atEndOfMonth();
        LocalDate dayBefore = period.from().atDay(1).minusDays(1);

        FinanceDashboardView money = Safely.read("the finance dashboard", () -> finance.build(period, today));
        ClaimsDashboardView claimsView = Safely.read("the claims dashboard", () -> claims.build(true, period, today));
        UnderwritingDashboardView cases = Safely.read("the underwriting dashboard",
            () -> underwriting.build(period, today));
        // ManagementDashboard reads the figures "policies" and "lapsed" of PoliciesInForceReport, and "open" of
        // ClaimsInProgressReport, by key.
        ReportResult inForce = Safely.read("policies in force",
            () -> reporting.run("policies-in-force", ReportFilter.asOf(end).withCompareAsOf(dayBefore)));
        ReportResult inProgress = Safely.read("claims in progress",
            () -> reporting.run("claims-in-progress", ReportFilter.asOf(end)));

        return new ManagementDashboardView(
            period.toView(),
            money == null ? null : money.collected(), money == null ? null : money.collectedBefore(),
            money == null ? null : money.collectedChangePercent(),
            money == null ? null : money.newPolicies(), money == null ? null : money.newPoliciesBefore(),
            money == null ? null : money.newPoliciesChangePercent(),
            whole(ReportFigures.figure(inForce, "policies")),
            money == null ? null : money.collections(),
            whole(ReportFigures.figure(inForce, "lapsed")),
            claimsView == null ? null : claimsView.paid(),
            whole(ReportFigures.figure(inProgress, "open")),
            cases == null ? null : sum(cases.openCases(), cases.casesInReview()),
            claimsView == null ? null : sum(claimsView.registered(), claimsView.underAssessment(), claimsView.reopened()));
    }

    private static Long whole(BigDecimal value) {
        return value == null ? null : value.longValueExact();
    }

    /** Null when any part is unknown: a partial count would read as the whole. */
    private static Long sum(Long... parts) {
        long total = 0;
        for (Long p : parts) {
            if (p == null) return null;
            total += p;
        }
        return total;
    }
}
