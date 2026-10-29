package tz.co.nlolo.lifeplatform.distribution.api;

import java.time.Instant;
import java.util.UUID;

/**
 * An agent's login to the agent console (2026-10-28), as the agent's page shows it. {@code status} is NONE before
 * anyone has given one; then OPEN, CLOSED (by staff) or SUSPENDED (with the agent). {@code delivery} is how the last
 * password reached the agent: EMAIL_LINK, SMS_PASSWORD or TEMPORARY_PASSWORD.
 */
public record AgentLoginView(UUID agentId, String status, String username, String delivery, String createdBy,
                             Instant createdAt, Instant passwordSentAt, String closedBy, Instant closedAt) {

    public static AgentLoginView none(UUID agentId) {
        return new AgentLoginView(agentId, "NONE", null, null, null, null, null, null, null);
    }
}
