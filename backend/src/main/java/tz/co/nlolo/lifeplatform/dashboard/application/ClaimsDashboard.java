package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView.QueuedClaim;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView.SettlementMonth;
import tz.co.nlolo.lifeplatform.claims.api.ClaimMonth;
import tz.co.nlolo.lifeplatform.dashboard.api.CountPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.DaysPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.FinanceDashboardView.MonthPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.Share;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

@Service
public class ClaimsDashboard {

    static final int QUEUE_SIZE = 5;
    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final ClaimsApi claims;
    private final RecentActivity activity;

    public ClaimsDashboard(ClaimsApi claims, RecentActivity activity) {
        this.claims = claims;
        this.activity = activity;
    }

    /**
     * @param manager whether the reader decides settlements -- which queue is THEIR work
     * @param today the civil day in Africa/Dar_es_Salaam, supplied by the controller
     */
    public ClaimsDashboardView build(boolean manager, LocalDate today) {
        return build(manager, DashboardPeriod.of(null, null, today), today);
    }

    /** @param period the months the ranged figures cover (2026-10-23); counts by status and the queue stay "now" */
    public ClaimsDashboardView build(boolean manager, DashboardPeriod period, LocalDate today) {
        return build(manager, period, today, null, null, null);
    }

    /**
     * One branch's claims (2026-11-23, branch views). A claim records no branch, so the branch is its policy's:
     * {@code policyNumbers} are the branch's policies, read from the reports' copy by the caller, and the claims
     * module counts its own claims on them by its own status rules. With no branch the reads are exactly the
     * every-branch ones.
     *
     * @param policyNumbers the branch's policy numbers; null for every branch
     */
    public ClaimsDashboardView build(boolean manager, DashboardPeriod period, LocalDate today, String branchCode,
                                     String branchName, java.util.Set<String> policyNumbers) {
        java.util.Set<String> pn = branchCode == null ? null : policyNumbers;
        boolean all = pn == null;
        // A manager decides a claim once it is assessed: UNDER_ASSESSMENT. SETTLEMENT_REQUESTED is already approved,
        // its payment on the way -- the queue listed those as "awaiting your decision" until 2026-11-04.
        ClaimStatus queueStatus = manager ? ClaimStatus.UNDER_ASSESSMENT : ClaimStatus.REGISTERED;
        List<QueuedClaim> queue = Safely.read("the " + queueStatus + " queue", () -> claims.searchClaims(
                queueStatus, null, pn, null, PageRequest.of(0, QUEUE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
            .map(c -> new QueuedClaim(c.claimId(), c.policyNumber(), c.claimantPartyId(), c.claimType().name(),
                c.dateOfEvent(), c.status().name()))
            .getContent());

        // The question no per-claim screen could answer: how many claims are waiting on a document, desk-wide.
        Long openRequests = Safely.read("open document requests",
            () -> all ? claims.countOpenDocumentRequests() : claims.countOpenDocumentRequests(pn));

        // TWELVE complete months ending with the last complete one (phase 3): one read serving the card, which
        // shows the last complete month, and the chart, which shows all twelve. The current month is in neither --
        // a part-month total of benefits paid invites comparison with full months and loses it.
        YearMonth lastComplete = YearMonth.from(today).minusMonths(1);
        List<SettlementMonth> twelve = Safely.read("settlements by month", () ->
            (all ? claims.settlementsByMonth(lastComplete.minusMonths(11), lastComplete)
                : claims.settlementsByMonth(lastComplete.minusMonths(11), lastComplete, pn)).stream()
                .map(s -> new SettlementMonth(s.month().toString(), s.currency(), s.claims(),
                    Money.of(s.totalApproved(), s.currency())))
                .toList());
        List<SettlementMonth> settlementSeries = twelve == null ? List.of() : twelve;
        // The cards read the last complete month alone, out of the twelve. Which month that is stays a SERVER
        // decision: a panel that picked the newest row it happened to receive would be inferring it.
        List<SettlementMonth> settlements = settlementSeries.stream()
            .filter(s -> lastComplete.toString().equals(s.month()))
            .toList();

        List<ActivityItem> recent = Safely.read("recent activity", () -> activity.latest("claims."));

        // ---- the ranged figures (2026-10-23). Money in the working currency only, never added across currencies ----
        Map<YearMonth, BigDecimal> paidByMonth = Safely.read("benefits paid", () ->
            (all ? claims.settlementsByMonth(period.compareFrom(), period.to())
                : claims.settlementsByMonth(period.compareFrom(), period.to(), pn)).stream()
                .filter(s -> WORKING_CURRENCY.equals(s.currency()))
                .collect(Collectors.toMap(tz.co.nlolo.lifeplatform.claims.api.SettlementMonth::month,
                    tz.co.nlolo.lifeplatform.claims.api.SettlementMonth::totalApproved, BigDecimal::add)));
        BigDecimal paidNow = paidByMonth == null ? null : sum(period.months(), paidByMonth);
        BigDecimal paidBefore = paidByMonth == null ? null : sum(period.compareMonths(), paidByMonth);
        List<MonthPoint> paidSeries = paidByMonth == null ? List.of() : period.months().stream()
            .map(m -> new MonthPoint(m.toString(), Money.of(paidByMonth.getOrDefault(m, BigDecimal.ZERO), WORKING_CURRENCY)))
            .toList();
        List<Share> byType = Safely.read("benefits paid by type", () -> {
            var rows = (all ? claims.settlementsByType(period.from(), period.to())
                    : claims.settlementsByType(period.from(), period.to(), pn)).stream()
                .filter(s -> WORKING_CURRENCY.equals(s.currency())).toList();
            return DashboardPeriod.shares(rows.stream()
                    .map(s -> new DashboardPeriod.Slice(s.claimType(), s.totalApproved())).toList(),
                v -> Money.of(v, WORKING_CURRENCY), null);
        });
        Map<YearMonth, ClaimMonth> settledByMonth = Safely.read("settlement turnaround", () ->
            (all ? claims.settlementTurnaroundByMonth(period.from(), period.to())
                : claims.settlementTurnaroundByMonth(period.from(), period.to(), pn)).stream()
                .collect(Collectors.toMap(ClaimMonth::month, c -> c)));
        Map<YearMonth, ClaimMonth> registeredByMonth = Safely.read("claims registered", () ->
            (all ? claims.registrationsByMonth(period.from(), period.to())
                : claims.registrationsByMonth(period.from(), period.to(), pn)).stream()
                .collect(Collectors.toMap(ClaimMonth::month, c -> c)));
        Long settledNow = settledByMonth == null ? null
            : settledByMonth.values().stream().mapToLong(ClaimMonth::claims).sum();
        Long registeredNow = registeredByMonth == null ? null
            : registeredByMonth.values().stream().mapToLong(ClaimMonth::claims).sum();
        String averageDays = settledByMonth == null ? null : DashboardPeriod.average(
            settledByMonth.values().stream().map(ClaimMonth::totalDays).reduce(BigDecimal.ZERO, BigDecimal::add),
            settledNow);

        return new ClaimsDashboardView(count(ClaimStatus.REGISTERED, pn), count(ClaimStatus.UNDER_ASSESSMENT, pn),
            count(ClaimStatus.SETTLEMENT_REQUESTED, pn), count(ClaimStatus.APPROVED, pn), count(ClaimStatus.REOPENED, pn),
            count(ClaimStatus.SETTLED, pn), openRequests,
            settlements, settlementSeries,
            queueStatus.name(), queue == null ? List.of() : queue,
            recent == null ? List.of() : recent,
            period.toView(), Money.of(paidNow, WORKING_CURRENCY), Money.of(paidBefore, WORKING_CURRENCY),
            DashboardPeriod.changePercent(paidNow, paidBefore), paidSeries, byType == null ? List.of() : byType,
            settledNow, registeredNow,
            counts(period, registeredByMonth), counts(period, settledByMonth),
            averageDays,
            settledByMonth == null ? List.of() : period.months().stream().map(m -> {
                ClaimMonth c = settledByMonth.get(m);
                return new DaysPoint(m.toString(), c == null ? null : DashboardPeriod.average(c.totalDays(), c.claims()));
            }).toList(),
            // Recent activity is the activity log's, which records no branch.
            all ? null : new tz.co.nlolo.lifeplatform.dashboard.api.BranchScope(branchCode, branchName,
                List.of("recentActivity")));
    }

    /** PRODUCT.md: "Working currency is TZS." */
    static final String WORKING_CURRENCY = "TZS";

    private static BigDecimal sum(List<YearMonth> months, Map<YearMonth, BigDecimal> byMonth) {
        return months.stream().map(m -> byMonth.getOrDefault(m, BigDecimal.ZERO)).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<CountPoint> counts(DashboardPeriod period, Map<YearMonth, ClaimMonth> byMonth) {
        if (byMonth == null) return List.of();
        return period.months().stream()
            .map(m -> new CountPoint(m.toString(), byMonth.containsKey(m) ? byMonth.get(m).claims() : 0L))
            .toList();
    }

    private Long count(ClaimStatus status, java.util.Set<String> policyNumbers) {
        return Safely.read(status + " claims",
            () -> claims.searchClaims(status, null, policyNumbers, null, TOTAL_ONLY).getTotalElements());
    }
}
