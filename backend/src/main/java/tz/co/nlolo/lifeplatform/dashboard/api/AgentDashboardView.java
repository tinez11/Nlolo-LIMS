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
 */
public record AgentDashboardView(Long clients, Long clientsAwaitingKyc, Long clientsKycRejected, Long policiesInBook,
                                 String commissionPeriod, Money commissionEarned, String commissionStatus,
                                 List<ClientAwaitingKyc> awaitingKyc) {

    public record ClientAwaitingKyc(UUID partyId, String displayName, String kycStatus) {}
}
