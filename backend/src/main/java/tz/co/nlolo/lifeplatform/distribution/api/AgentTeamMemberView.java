package tz.co.nlolo.lifeplatform.distribution.api;

import java.util.UUID;

/** One agent in a caller's team -- the caller's own profile or one below it. {@code displayName} is the agent's
 * party name, null when it cannot be resolved. */
public record AgentTeamMemberView(UUID agentId, String licenseNumber, LicenseStatus licenseStatus,
                                  UUID hierarchyParentId, String displayName) {}
