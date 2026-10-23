package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;
import tz.co.nlolo.lifeplatform.dashboard.api.DaysPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.Share;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView.OutcomeSeriesPoint;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionOutcome;
import tz.co.nlolo.lifeplatform.underwriting.api.DecisionTurnaround;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView.DecisionMonth;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView.DecisionSeriesPoint;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView.QueuedCase;
import tz.co.nlolo.lifeplatform.underwriting.api.CaseFilter;
import tz.co.nlolo.lifeplatform.underwriting.api.ReferralStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
public class UnderwritingDashboard {

    static final int QUEUE_SIZE = 5;
    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final UnderwritingApi underwriting;
    private final RecentActivity activity;

    public UnderwritingDashboard(UnderwritingApi underwriting, RecentActivity activity) {
        this.underwriting = underwriting;
        this.activity = activity;
    }

    /**
     * @param today the civil day in Africa/Dar_es_Salaam, supplied by the controller -- the composer reads no clock,
     *     because a second Clock bean breaks Modulith and a composer that knew the date could not be tested on one
     */
    public UnderwritingDashboardView build(LocalDate today) {
        return build(DashboardPeriod.of(null, null, today), today);
    }

    /** @param period the months the ranged figures cover (2026-10-23); counts and the queue stay "now" */
    public UnderwritingDashboardView build(DashboardPeriod period, LocalDate today) {
        Long open = Safely.read("open cases",
            () -> underwriting.listCases(UnderwritingCaseStatus.OPEN, null, null, TOTAL_ONLY).getTotalElements());
        Long inReview = Safely.read("cases in review",
            () -> underwriting.listCases(UnderwritingCaseStatus.IN_REVIEW, null, null, TOTAL_ONLY).getTotalElements());
        Long awaitingIssue = Safely.read("cases awaiting issue",
            () -> underwriting.listCasesAwaitingIssue(null, TOTAL_ONLY).getTotalElements());

        // Oldest first: "next in your queue" means what has waited longest, where the queue PAGE sorts newest first.
        List<QueuedCase> queue = Safely.read("the open-case queue", () -> underwriting.listCases(
                UnderwritingCaseStatus.OPEN, null, null,
                PageRequest.of(0, QUEUE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
            .map(c -> new QueuedCase(c.caseId(), c.applicantPartyId(), c.lifeAssuredPartyId(), c.productId(),
                Money.of(c.sumAssuredAmount(), c.sumAssuredCurrency()), c.status().name()))
            .getContent());

        // Waiting on a senior. Referral is tracked independently of the decision, so this is its own dimension
        // rather than a status: a referred case is still OPEN.
        Long referred = Safely.read("cases referred to a senior", () -> underwriting.listCases(
            CaseFilter.none().withReferral(ReferralStatus.REFERRED_TO_SENIOR), TOTAL_ONLY).getTotalElements());

        // TWELVE complete months, ending with the last complete one (phase 3). ONE read serves both the cards and
        // the chart: the cards show the last complete month, the chart all twelve. The current month is never in
        // either -- it is always partial, and a turnaround averaged over three days of it would not compare with a
        // full month's.
        YearMonth lastComplete = YearMonth.from(today).minusMonths(1);
        List<tz.co.nlolo.lifeplatform.underwriting.api.DecisionMonth> twelve = Safely.read("decisions by month",
            () -> underwriting.decisionsByMonth(lastComplete.minusMonths(11), lastComplete));
        List<tz.co.nlolo.lifeplatform.underwriting.api.DecisionMonth> rows = twelve == null ? List.of() : twelve;

        List<DecisionMonth> decisions = rows.stream()
            .filter(d -> lastComplete.equals(d.month()))
            .map(d -> new DecisionMonth(d.month().toString(), d.outcome().name(), d.cases(),
                d.averageDays() == null ? null : d.averageDays().toPlainString()))
            .toList();

        List<DecisionSeriesPoint> decisionSeries = decisionSeries(rows);

        List<ActivityItem> recent = Safely.read("recent activity", () -> activity.latest("underwriting."));

        // ---- the ranged figures (2026-10-23): one read each for the period and its comparison months ----
        List<tz.co.nlolo.lifeplatform.underwriting.api.DecisionMonth> inPeriod = Safely.read("decisions in the period",
            () -> underwriting.decisionsByMonth(period.from(), period.to()));
        Long decidedBefore = Safely.read("decisions a year earlier",
            () -> underwriting.decisionsByMonth(period.compareFrom(), period.compareTo()).stream()
                .mapToLong(tz.co.nlolo.lifeplatform.underwriting.api.DecisionMonth::cases).sum());
        Map<YearMonth, DecisionTurnaround> days = Safely.read("decision turnaround", () ->
            underwriting.decisionTurnaroundByMonth(period.from(), period.to()).stream()
                .collect(Collectors.toMap(DecisionTurnaround::month, d -> d)));

        Long decided = null;
        String acceptance = null;
        List<Share> totals = List.of();
        List<OutcomeSeriesPoint> outcomes = List.of();
        if (inPeriod != null) {
            Map<DecisionOutcome, Long> byOutcome = new EnumMap<>(DecisionOutcome.class);
            Map<YearMonth, long[]> byMonth = new TreeMap<>();
            for (var row : inPeriod) {
                byOutcome.merge(row.outcome(), row.cases(), Long::sum);
                long[] m = byMonth.computeIfAbsent(row.month(), k -> new long[3]);
                switch (row.outcome()) {
                    case ACCEPT, LOADED -> m[0] += row.cases();
                    case POSTPONED -> m[1] += row.cases();
                    case DECLINED -> m[2] += row.cases();
                }
            }
            decided = byOutcome.values().stream().mapToLong(Long::longValue).sum();
            BigDecimal whole = BigDecimal.valueOf(decided);
            long accepted = byOutcome.getOrDefault(DecisionOutcome.ACCEPT, 0L) + byOutcome.getOrDefault(DecisionOutcome.LOADED, 0L);
            acceptance = DashboardPeriod.percentOf(BigDecimal.valueOf(accepted), whole);
            totals = byOutcome.entrySet().stream()
                .filter(e -> e.getValue() > 0)
                .sorted(Map.Entry.<DecisionOutcome, Long>comparingByValue().reversed())
                .map(e -> new Share(e.getKey().name(), null, e.getValue(),
                    DashboardPeriod.percentOf(BigDecimal.valueOf(e.getValue()), whole)))
                .toList();
            outcomes = period.months().stream().map(mo -> {
                long[] m = byMonth.getOrDefault(mo, new long[3]);
                return new OutcomeSeriesPoint(mo.toString(), m[0], m[1], m[2]);
            }).toList();
        }
        String averageDays = null;
        List<DaysPoint> turnaround = List.of();
        if (days != null) {
            BigDecimal allDays = days.values().stream().map(DecisionTurnaround::totalDays).reduce(BigDecimal.ZERO, BigDecimal::add);
            long allCases = days.values().stream().mapToLong(DecisionTurnaround::cases).sum();
            averageDays = DashboardPeriod.average(allDays, allCases);
            turnaround = period.months().stream().map(mo -> {
                DecisionTurnaround d = days.get(mo);
                return new DaysPoint(mo.toString(), d == null ? null : DashboardPeriod.average(d.totalDays(), d.cases()));
            }).toList();
        }
        Long decidedNow = decided;

        return new UnderwritingDashboardView(open, inReview, awaitingIssue, referred, decisions, decisionSeries,
            queue == null ? List.of() : queue,
            recent == null ? List.of() : recent,
            period.toView(), decidedNow, decidedBefore,
            decidedNow == null || decidedBefore == null ? null
                : DashboardPeriod.changePercent(BigDecimal.valueOf(decidedNow), BigDecimal.valueOf(decidedBefore)),
            acceptance, averageDays, totals, outcomes, turnaround);
    }

    /**
     * The chart's two series, grouped per month: ACCEPT plus LOADED against DECLINED.
     *
     * <p>The addition happens here and not in the browser. Both are acceptances -- a loading is an acceptance that
     * costs more -- and a console that summed them would be the first exception to "the browser never adds".
     * POSTPONED is in neither: it closed no case.
     *
     * <p>A month with no decisions at all is ABSENT rather than a zero point, so the chart draws a gap. The
     * platform said nothing about that month; a zero would say nothing happened, which is a different claim.
     */
    private static List<DecisionSeriesPoint> decisionSeries(
            List<tz.co.nlolo.lifeplatform.underwriting.api.DecisionMonth> rows) {
        java.util.Map<YearMonth, long[]> byMonth = new java.util.TreeMap<>();
        for (var row : rows) {
            long[] totals = byMonth.computeIfAbsent(row.month(), m -> new long[2]);
            switch (row.outcome()) {
                case ACCEPT, LOADED -> totals[0] += row.cases();
                case DECLINED -> totals[1] += row.cases();
                case POSTPONED -> { }
            }
        }
        return byMonth.entrySet().stream()
            .map(e -> new DecisionSeriesPoint(e.getKey().toString(), e.getValue()[0], e.getValue()[1]))
            .toList();
    }
}
