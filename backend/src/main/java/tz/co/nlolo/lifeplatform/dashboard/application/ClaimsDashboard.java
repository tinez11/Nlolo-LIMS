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
import tz.co.nlolo.lifeplatform.dashboard.api.Money;

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

        return new ClaimsDashboardView(count(ClaimStatus.REGISTERED), count(ClaimStatus.UNDER_ASSESSMENT),
            count(ClaimStatus.SETTLEMENT_REQUESTED), count(ClaimStatus.APPROVED), count(ClaimStatus.REOPENED),
            count(ClaimStatus.SETTLED), openRequests,
            settlements, settlementSeries,
            queueStatus.name(), queue == null ? List.of() : queue,
            recent == null ? List.of() : recent);
    }

    private Long count(ClaimStatus status) {
        return Safely.read(status + " claims",
            () -> claims.searchClaims(status, null, null, null, TOTAL_ONLY).getTotalElements());
    }
}
