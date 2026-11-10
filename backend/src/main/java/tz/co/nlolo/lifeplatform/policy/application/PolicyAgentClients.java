package tz.co.nlolo.lifeplatform.policy.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.distribution.api.DistributionApi;
import tz.co.nlolo.lifeplatform.party.api.AgentClients;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * An agent's clients are the people on the policies their team sold (2026-11-10) -- owner, life assured or named
 * beneficiary, the connection {@code relatedPartyId} already means for the claims desk. Party asks this before it
 * shows an agent a person they did not register themselves.
 */
@Component
class PolicyAgentClients implements AgentClients {

    private final PolicyApi policies;
    private final DistributionApi distribution;

    PolicyAgentClients(PolicyApi policies, DistributionApi distribution) {
        this.policies = policies;
        this.distribution = distribution;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isClientOf(UUID agentPartyId, UUID partyId) {
        List<UUID> team = distribution.resolveAgentTeam(agentPartyId);
        if (team.isEmpty()) return false;
        return policies.searchPolicies(null, partyId, null, new HashSet<>(team), null, PageRequest.of(0, 1))
            .hasContent();
    }
}
