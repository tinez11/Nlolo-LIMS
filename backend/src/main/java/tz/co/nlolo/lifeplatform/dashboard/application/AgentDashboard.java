package tz.co.nlolo.lifeplatform.dashboard.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView;
import tz.co.nlolo.lifeplatform.dashboard.api.AgentDashboardView.ClientAwaitingKyc;
import tz.co.nlolo.lifeplatform.dashboard.api.Money;
import tz.co.nlolo.lifeplatform.distribution.api.CommissionStatementView;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.KycStatus;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentDashboard {

    static final int QUEUE_SIZE = 5;
    private static final PageRequest TOTAL_ONLY = PageRequest.of(0, 1);

    private final PartyApi parties;
    private final PolicyApi policies;
    private final DistributionApi distribution;

    public AgentDashboard(PartyApi parties, PolicyApi policies, DistributionApi distribution) {
        this.parties = parties;
        this.policies = policies;
        this.distribution = distribution;
    }

    /**
     * @param subject the token subject -- what clients are scoped on, as {@code PartyController} scopes them
     * @param partyId the token's {@code party_id} -- what the hierarchy team and the agent profile hang off
     * @param today the civil date in Africa/Dar_es_Salaam
     */
    public AgentDashboardView build(String subject, UUID partyId, LocalDate today) {
        Long clients = Safely.read("my clients",
            () -> parties.searchParties(null, subject, null, null, TOTAL_ONLY).getTotalElements());
        Long awaitingKyc = Safely.read("clients awaiting KYC",
            () -> parties.searchParties(KycStatus.PENDING, subject, null, null, TOTAL_ONLY).getTotalElements());
        Long rejected = Safely.read("clients with KYC rejected",
            () -> parties.searchParties(KycStatus.REJECTED, subject, null, null, TOTAL_ONLY).getTotalElements());
        // Newest first, the same order the party search uses everywhere: an agent's most recent visit is the one
        // whose documents are in hand.
        List<ClientAwaitingKyc> queue = Safely.read("the KYC queue", () -> parties.searchParties(KycStatus.PENDING,
                subject, null, null, PageRequest.of(0, QUEUE_SIZE,
                    Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("partyId"))))
            .map(p -> new ClientAwaitingKyc(p.partyId(), p.displayName(), p.kycStatus().name()))
            .getContent());

        Long book = Safely.read("policies in my book", () -> policies.searchPolicies(null, null, null,
            Set.copyOf(distribution.resolveAgentTeam(partyId)), null, TOTAL_ONLY).getTotalElements());

        String period = YearMonth.from(today).minusMonths(1).toString();
        CommissionStatementView statement = Safely.read("commission statement", () -> distribution
            .agentIdForParty(partyId)
            .flatMap(agentId -> distribution.listStatements(agentId, period).stream().findFirst())
            .orElse(null));

        return new AgentDashboardView(clients, awaitingKyc, rejected, book, period,
            statement == null ? null : Money.of(statement.totalAmount(), statement.totalCurrency()),
            statement == null || statement.status() == null ? null : statement.status().name(),
            queue == null ? List.of() : queue);
    }
}
