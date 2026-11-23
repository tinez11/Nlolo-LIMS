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

    /**
     * One branch's overview (2026-11-23, branch views). Every figure is the branch's own, read from the report or
     * dashboard its card opens with the same branch: premiums collected and new policies from the premium-collections
     * and new-business reports (the finance dashboard is company-wide), policies in force, lapsed and open claims from
     * their reports, claims paid and waiting from the branch's claims dashboard, cases waiting from the branch's
     * underwriting dashboard. The collections block stays the company's and is named under {@code allBranches}.
     * With no branch, exactly {@link #build(DashboardPeriod, LocalDate)}.
     */
    public ManagementDashboardView build(DashboardPeriod period, LocalDate today, String branchCode) {
        if (branchCode == null) return build(period, today);
        String b = branchCode;
        LocalDate end = period.to().atEndOfMonth().isAfter(today) ? today : period.to().atEndOfMonth();
        LocalDate start = period.from().atDay(1);
        LocalDate dayBefore = start.minusDays(1);
        LocalDate compareStart = period.compareFrom().atDay(1);
        LocalDate compareEnd = period.compareTo().atEndOfMonth();
        String name = Safely.read("the branch's name", () -> reporting.branchName(b));

        BigDecimal collectedNow = figure("premiums collected", "premium-collections", "amount",
            ReportFilter.period(start, end).withBranchCode(b));
        BigDecimal collectedBefore = figure("premiums collected a year earlier", "premium-collections", "amount",
            ReportFilter.period(compareStart, compareEnd).withBranchCode(b));
        BigDecimal newNow = figure("new policies", "new-business", "policies",
            ReportFilter.period(start, end).withBranchCode(b));
        BigDecimal newBefore = figure("new policies a year earlier", "new-business", "policies",
            ReportFilter.period(compareStart, compareEnd).withBranchCode(b));

        // A claim's branch is its policy's. Unread, the claims figures stay unknown rather than becoming everyone's.
        java.util.Set<String> policies = Safely.read("the branch's policies", () -> reporting.policyNumbersInBranch(b));
        ClaimsDashboardView claimsView = policies == null ? null : Safely.read("the branch's claims dashboard",
            () -> claims.build(true, period, today, b, name, policies));
        UnderwritingDashboardView cases = Safely.read("the branch's underwriting dashboard",
            () -> underwriting.build(period, today, b, name));
        ReportResult inForce = Safely.read("policies in force",
            () -> reporting.run("policies-in-force", ReportFilter.asOf(end).withCompareAsOf(dayBefore).withBranchCode(b)));
        ReportResult inProgress = Safely.read("claims in progress",
            () -> reporting.run("claims-in-progress", ReportFilter.asOf(end).withBranchCode(b)));
        FinanceDashboardView money = Safely.read("the finance dashboard", () -> finance.build(period, today));

        return new ManagementDashboardView(
            period.toView(),
            tz.co.nlolo.lifeplatform.dashboard.api.Money.of(collectedNow, ClaimsDashboard.WORKING_CURRENCY),
            tz.co.nlolo.lifeplatform.dashboard.api.Money.of(collectedBefore, ClaimsDashboard.WORKING_CURRENCY),
            DashboardPeriod.changePercent(collectedNow, collectedBefore),
            whole(newNow), whole(newBefore), DashboardPeriod.changePercent(newNow, newBefore),
            whole(ReportFigures.figure(inForce, "policies")),
            money == null ? null : money.collections(),
            whole(ReportFigures.figure(inForce, "lapsed")),
            claimsView == null ? null : claimsView.paid(),
            whole(ReportFigures.figure(inProgress, "open")),
            cases == null ? null : sum(cases.openCases(), cases.casesInReview()),
            claimsView == null ? null : sum(claimsView.registered(), claimsView.underAssessment(), claimsView.reopened()),
            new tz.co.nlolo.lifeplatform.dashboard.api.BranchScope(b, name, java.util.List.of("collections")));
    }

    private BigDecimal figure(String what, String report, String key, ReportFilter filter) {
        ReportResult result = Safely.read(what, () -> reporting.run(report, filter));
        return ReportFigures.figure(result, key);
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
