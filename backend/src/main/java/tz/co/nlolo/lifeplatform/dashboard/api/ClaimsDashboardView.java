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
                                  Long reopened, Long settled, Long openDocumentRequests,
                                  List<SettlementMonth> settlements,
                                  List<SettlementMonth> settlementSeries,
                                  String queueStatus, List<QueuedClaim> queue,
                                  List<ActivityItem> recentActivity,
                                  Period period, Money paid, Money paidBefore, String paidChangePercent,
                                  List<FinanceDashboardView.MonthPoint> paidSeries, List<Share> paidByType,
                                  Long settledInPeriod, Long registeredInPeriod,
                                  List<CountPoint> registeredSeries, List<CountPoint> settledSeries,
                                  String averageDaysToSettle, List<DaysPoint> turnaroundSeries) {

    public record QueuedClaim(UUID claimId, String policyNumber, UUID claimantPartyId, String claimType,
                              LocalDate dateOfEvent, String status) {}

    /**
     * One month's settled claims in one currency (phase 2, 2026-10-15).
     *
     * <p>Per currency, never added across: a total of TZS and USD is a number in no currency. The dashboard's own
     * shape rather than {@code claims.api.SettlementMonth}, so the wire contract stays in this module's vocabulary.
     */
    public record SettlementMonth(String month, String currency, long claims, Money totalApproved) {}
}
