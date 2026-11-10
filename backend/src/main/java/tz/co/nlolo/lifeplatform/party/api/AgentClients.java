package tz.co.nlolo.lifeplatform.party.api;

import java.util.UUID;

/**
 * Whether a person is one of an agent's clients by the policies the agent's team sold them (2026-11-10).
 *
 * <p>An agent read a party only if they had registered it themselves, so a policy the agent sold to a client staff had
 * registered showed its policyholder as a raw id on the agent's policy page. Who is in an agent's book is the policy
 * module's knowledge and party may not depend on policy, so policy implements this and party asks it.
 */
public interface AgentClients {

    /** True when {@code partyId} holds, or is covered by, a policy whose agent of record is in this agent's team. */
    boolean isClientOf(UUID agentPartyId, UUID partyId);
}
