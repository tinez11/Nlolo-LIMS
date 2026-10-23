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
import tz.co.nlolo.lifeplatform.claims.api.SettlementByType;
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
        ClaimStatus queueStatus = manager ? ClaimStatus.SETTLEMENT_REQUESTED : ClaimStatus.REGISTERED;
        List<QueuedClaim> queue = Safely.read("the " + queueStatus + " queue", () -> claims.searchClaims(
                queueStatus, null, null, null, PageRequest.of(0, QUEUE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
            .map(c -> new QueuedClaim(c.claimId(), c.policyNumber(), c.claimantPartyId(), c.claimType().name(),
                c.dateOfEvent(), c.status().name()))
            .getContent());

        // The question no per-claim screen could answer: how many claims are waiting on a document, desk-wide.
        Long openRequests = Safely.read("open document requests", claims::countOpenDocumentRequests);

        // TWELVE complete months ending with the last complete one (phase 3): one read serving the card, which
        // shows the last complete month, and the chart, which shows all twelve. The current month is in neither --
        // a part-month total of benefits paid invites comparison with full months and loses it.
        YearMonth lastComplete = YearMonth.from(today).minusMonths(1);
        List<SettlementMonth> twelve = Safely.read("settlements by month", () ->
            claims.settlementsByMonth(lastComplete.minusMonths(11), lastComplete).stream()
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
            claims.settlementsByMonth(period.compareFrom(), period.to()).stream()
                .filter(s -> WORKING_CURRENCY.equals(s.currency()))
                .collect(Collectors.toMap(tz.co.nlolo.lifeplatform.claims.api.SettlementMonth::month,
                    tz.co.nlolo.lifeplatform.claims.api.SettlementMonth::totalApproved, BigDecimal::add)));
        BigDecimal paidNow = paidByMonth == null ? null : sum(period.months(), paidByMonth);
        BigDecimal paidBefore = paidByMonth == null ? null : sum(period.compareMonths(), paidByMonth);
        List<MonthPoint> paidSeries = paidByMonth == null ? List.of() : period.months().stream()
            .map(m -> new MonthPoint(m.toString(), Money.of(paidByMonth.getOrDefault(m, BigDecimal.ZERO), WORKING_CURRENCY)))
            .toList();
        List<Share> byType = Safely.read("benefits paid by type", () -> {
            var rows = claims.settlementsByType(period.from(), period.to()).stream()
                .filter(s -> WORKING_CURRENCY.equals(s.currency())).toList();
            BigDecimal whole = rows.stream().map(SettlementByType::totalApproved).reduce(BigDecimal.ZERO, BigDecimal::add);
            return rows.stream().map(s -> new Share(s.claimType(), Money.of(s.totalApproved(), WORKING_CURRENCY),
                s.claims(), DashboardPeriod.percentOf(s.totalApproved(), whole))).toList();
        });
        Map<YearMonth, ClaimMonth> settledByMonth = Safely.read("settlement turnaround", () ->
            claims.settlementTurnaroundByMonth(period.from(), period.to()).stream()
                .collect(Collectors.toMap(ClaimMonth::month, c -> c)));
        Map<YearMonth, ClaimMonth> registeredByMonth = Safely.read("claims registered", () ->
            claims.registrationsByMonth(period.from(), period.to()).stream()
                .collect(Collectors.toMap(ClaimMonth::month, c -> c)));
        Long settledNow = settledByMonth == null ? null
            : settledByMonth.values().stream().mapToLong(ClaimMonth::claims).sum();
        Long registeredNow = registeredByMonth == null ? null
            : registeredByMonth.values().stream().mapToLong(ClaimMonth::claims).sum();
        String averageDays = settledByMonth == null ? null : DashboardPeriod.average(
            settledByMonth.values().stream().map(ClaimMonth::totalDays).reduce(BigDecimal.ZERO, BigDecimal::add),
            settledNow);

        return new ClaimsDashboardView(count(ClaimStatus.REGISTERED), count(ClaimStatus.UNDER_ASSESSMENT),
            count(ClaimStatus.SETTLEMENT_REQUESTED), count(ClaimStatus.APPROVED), count(ClaimStatus.REOPENED),
            count(ClaimStatus.SETTLED), openRequests,
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
            }).toList());
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

    private Long count(ClaimStatus status) {
        return Safely.read(status + " claims",
            () -> claims.searchClaims(status, null, null, null, TOTAL_ONLY).getTotalElements());
    }
}
