package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimsApi;
import tz.co.nlolo.lifeplatform.dashboard.api.ActivityItem;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.ClaimsDashboardView.QueuedClaim;

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

    /** @param manager whether the reader decides settlements -- which queue is THEIR work */
    public ClaimsDashboardView build(boolean manager) {
        ClaimStatus queueStatus = manager ? ClaimStatus.SETTLEMENT_REQUESTED : ClaimStatus.REGISTERED;
        List<QueuedClaim> queue = Safely.read("the " + queueStatus + " queue", () -> claims.searchClaims(
                queueStatus, null, null, null, PageRequest.of(0, QUEUE_SIZE, Sort.by(Sort.Direction.ASC, "createdAt")))
            .map(c -> new QueuedClaim(c.claimId(), c.policyNumber(), c.claimantPartyId(), c.claimType().name(),
                c.dateOfEvent(), c.status().name()))
            .getContent());

        List<ActivityItem> recent = Safely.read("recent activity", () -> activity.latest("claims."));

        return new ClaimsDashboardView(count(ClaimStatus.REGISTERED), count(ClaimStatus.UNDER_ASSESSMENT),
            count(ClaimStatus.SETTLEMENT_REQUESTED), count(ClaimStatus.APPROVED), count(ClaimStatus.REOPENED),
            count(ClaimStatus.SETTLED), queueStatus.name(), queue == null ? List.of() : queue,
            recent == null ? List.of() : recent);
    }

    private Long count(ClaimStatus status) {
        return Safely.read(status + " claims",
            () -> claims.searchClaims(status, null, null, null, TOTAL_ONLY).getTotalElements());
    }
}
