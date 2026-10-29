package tz.co.nlolo.lifeplatform.policy.api;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * An agent acts only on their own book (2026-10-27).
 *
 * <p>GET /policies/{n} kept an agent to policies they or their downline sold, but every other endpoint an agent could
 * reach by policy number or invoice -- request payment, read invoices and loans, take a loan, change beneficiaries,
 * file a field receipt -- checked only that the caller was an agent. Any agent could act on any customer in the
 * company. Each of those endpoints now asks this, with the policy it is about.
 *
 * <p>Staff and customers pass through untouched: staff are held by their permissions, customers by their own check.
 */
public final class AgentBook {

    private AgentBook() {}

    public static boolean isAgent(Authentication authentication) {
        return authentication != null && authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority).anyMatch("ROLE_REALM_AGENTS"::equals);
    }

    /** Refuses an agent a policy outside their book; anyone else is not this check's business. */
    public static void require(PolicyApi policies, String policyNumber, Jwt jwt, Authentication authentication) {
        if (!isAgent(authentication)) return;
        if (!policies.inAgentBook(policyNumber, agentParty(jwt))) {
            throw new AccessDeniedException("Access denied: agent may only act on policies in their own book of business");
        }
    }

    /** The signed-in agent's own agent id; refuses a token that names no agent. */
    public static UUID ownAgentId(PolicyApi policies, Jwt jwt) {
        return policies.agentIdOf(agentParty(jwt))
            .orElseThrow(() -> new AccessDeniedException("Access denied: this sign-in is not an agent's"));
    }

    private static UUID agentParty(Jwt jwt) {
        String partyId = jwt == null ? null : jwt.getClaimAsString("party_id");
        if (partyId == null) throw new AccessDeniedException("Agent token carries no party_id claim");
        return UUID.fromString(partyId);
    }
}
