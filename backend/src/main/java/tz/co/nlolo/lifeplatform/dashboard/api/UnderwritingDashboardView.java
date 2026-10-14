package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;
import java.util.UUID;

/**
 * The underwriter's dashboard. Every count is a search's total for one status; null means it could not be read.
 *
 * @param queue the open cases that have waited longest, oldest first -- empty when the count could not be read
 */
public record UnderwritingDashboardView(Long openCases, Long casesInReview, Long casesAwaitingIssue,
                                        List<QueuedCase> queue) {

    /** A case as the queue panel shows it. Names are resolved by the console from the ids, as the queue page does. */
    public record QueuedCase(UUID caseId, UUID applicantPartyId, UUID lifeAssuredPartyId, UUID productId,
                             Money sumAssured, String status) {}
}
