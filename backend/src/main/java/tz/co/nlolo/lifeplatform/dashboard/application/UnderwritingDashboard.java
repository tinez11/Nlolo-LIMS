package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView.DecisionMonth;
import tz.co.nlolo.lifeplatform.dashboard.api.UnderwritingDashboardView.QueuedCase;
import tz.co.nlolo.lifeplatform.underwriting.api.CaseFilter;
import tz.co.nlolo.lifeplatform.underwriting.api.ReferralStatus;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingCaseStatus;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

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

        // The LAST COMPLETE month only. The current month is always partial, and a turnaround averaged over three
        // days of it is not comparable with a full month's. Phase 3 widens this range for its chart and changes
        // nothing else.
        YearMonth lastComplete = YearMonth.from(today).minusMonths(1);
        List<DecisionMonth> decisions = Safely.read("decisions by month", () ->
            underwriting.decisionsByMonth(lastComplete, lastComplete).stream()
                .map(d -> new DecisionMonth(d.month().toString(), d.outcome().name(), d.cases(),
                    d.averageDays() == null ? null : d.averageDays().toPlainString()))
                .toList());

        List<ActivityItem> recent = Safely.read("recent activity", () -> activity.latest("underwriting."));

        return new UnderwritingDashboardView(open, inReview, awaitingIssue, referred,
            decisions == null ? List.of() : decisions,
            queue == null ? List.of() : queue,
            recent == null ? List.of() : recent);
    }
}
