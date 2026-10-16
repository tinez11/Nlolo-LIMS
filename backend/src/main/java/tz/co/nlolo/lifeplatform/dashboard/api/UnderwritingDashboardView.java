package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;
import java.util.UUID;

/**
 * The underwriter's dashboard. Every count is a search's total for one status; null means it could not be read.
 *
 * @param queue the open cases that have waited longest, oldest first -- empty when the count could not be read
 * @param recentActivity this module's latest events, newest first -- what happened, never who; empty when unreadable
 */
public record UnderwritingDashboardView(Long openCases, Long casesInReview, Long casesAwaitingIssue,
                                        Long casesReferred, List<DecisionMonth> decisions,
                                        List<DecisionSeriesPoint> decisionSeries,
                                        List<QueuedCase> queue, List<ActivityItem> recentActivity) {

    /** A case as the queue panel shows it. Names are resolved by the console from the ids, as the queue page does. */
    public record QueuedCase(UUID caseId, UUID applicantPartyId, UUID lifeAssuredPartyId, UUID productId,
                             Money sumAssured, String status) {}

    /**
     * One month's decisions of one outcome, and how long they took (phase 2, 2026-10-15).
     *
     * <p>The dashboard's OWN shape, not {@code underwriting.api.DecisionMonth}, for the reason {@link Money} exists:
     * this record is the wire contract the console generates its types from, and it stays in the dashboard's
     * vocabulary -- a month as a {@code YYYY-MM} string, a decimal as a string -- rather than changing shape
     * whenever another module's record does.
     *
     * @param averageDays mean calendar days from opening to decision, one decimal place, as a decimal string
     */
    public record DecisionMonth(String month, String outcome, long cases, String averageDays) {}

    /**
     * One month of the decisions chart: what was taken on, against what was refused (phase 3, 2026-10-16).
     *
     * <p><b>Grouped HERE, on the server.</b> {@code accepted} is ACCEPT plus LOADED -- both are acceptances, one
     * simply costs more -- and the browser may not add them, not even counts: "the browser never adds" is only a
     * rule while it has no exceptions.
     *
     * <p>POSTPONED is in neither number. It is not a decision that closed a case, and a third bar a month would
     * cost the chart its readability for a figure the card beside it already shows.
     */
    public record DecisionSeriesPoint(String month, long accepted, long declined) {}
}
