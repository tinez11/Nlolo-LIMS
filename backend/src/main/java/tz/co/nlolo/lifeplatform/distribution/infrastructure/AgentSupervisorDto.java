package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import java.util.UUID;

/**
 * Who an agent reports to, as the agent sees it on their own profile (2026-11-08, the user: "your supervisor's own
 * profile is not reachable from here"). Contact details only: the supervisor's figures stay closed to the agent below.
 */
public record AgentSupervisorDto(UUID agentId, String displayName, String licenseNumber, String phoneNumber) {}
