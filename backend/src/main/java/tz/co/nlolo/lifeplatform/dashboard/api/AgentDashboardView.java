package tz.co.nlolo.lifeplatform.dashboard.api;

import java.util.List;
import java.util.UUID;

/**
 * An agent's dashboard, scoped exactly as the agent's own screens are: clients by who registered them, policies by
 * the agent's hierarchy team.
 *
 * @param commissionPeriod the last closed period ("YYYY-MM"), the one {@code commissionEarned} is for
 * @param commissionEarned null when the party is not an agent here, or has no statement for that period
 * @param awaitingKyc the newest clients whose KYC is still pending, so the agent can send their documents
 * @param period the months the ranged figures below cover (2026-10-23)
 * @param commissionInPeriod every statement of the period added together, in the agent's statement currency
 * @param commissionSeries every month of the period: what was paid and what is still to be paid
 * @param sold policies issued in the period on the agent's team
 * @param bookByProduct the team's policies in force today per product category, with each one's share
 * @param premiumSold the yearly premium of the regular-premium policies sold in the period, savings left out
 *     (2026-11-12, analytics plan 3); "sold" means cover started (D1) -- {@code sold} counts the same way since
 * @param singlePremiumsSold one-time premiums sold, apart (D2)
 * @param savingsDepositsTaken the opening deposit of each savings or fixed-deposit policy sold, apart (D3)
 * @param customersPaid this month's premiums due on the agent's customers, and how much is paid (D4); null when unread
 */
public record AgentDashboardView(Long clients, Long clientsAwaitingKyc, Long clientsKycRejected, Long policiesInBook,
                                 Long applicationsInUnderwriting,
                                 String commissionPeriod, Money commissionEarned, String commissionStatus,
                                 List<ClientAwaitingKyc> awaitingKyc,
                                 Period period, Money commissionInPeriod, List<CommissionPoint> commissionSeries,
                                 Long sold, Long soldBefore, String soldChangePercent, List<CountPoint> soldSeries,
                                 List<Share> bookByProduct,
                                 Money premiumSold, Money premiumSoldBefore, String premiumSoldChangePercent,
                                 Money singlePremiumsSold, Money coverSold, Money savingsDepositsTaken,
                                 tz.co.nlolo.lifeplatform.reporting.api.CollectionFigures customersPaid) {

    public record ClientAwaitingKyc(UUID partyId, String displayName, String kycStatus) {}

    /**
     * One month's commission statement split by whether it has been paid. A month with no statement is zero in both:
     * no statement means nothing was earned that month.
     */
    public record CommissionPoint(String month, Money paid, Money pending) {}
}
