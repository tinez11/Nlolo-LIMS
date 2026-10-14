package tz.co.nlolo.lifeplatform.dashboard.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Every open claim by its stage, plus the queue that is the reader's own work. Counts are all-time totals for one
 * status -- there is no date filter on the claims search yet (phase 2), so "settled" means ever, and the card says so.
 *
 * @param queueStatus which status {@code queue} lists: SETTLEMENT_REQUESTED for a manager, REGISTERED for an assessor
 * @param recentActivity this module's latest events, newest first -- what happened, never who; empty when unreadable
 */
public record ClaimsDashboardView(Long registered, Long underAssessment, Long settlementRequested, Long approved,
                                  Long reopened, Long settled, String queueStatus, List<QueuedClaim> queue,
                                  List<ActivityItem> recentActivity) {

    public record QueuedClaim(UUID claimId, String policyNumber, UUID claimantPartyId, String claimType,
                              LocalDate dateOfEvent, String status) {}
}
