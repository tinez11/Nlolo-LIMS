package tz.co.nlolo.lifeplatform.distribution.api;

import java.time.LocalDate;
import java.util.UUID;

/** Read view of {@code distribution.domain.AgentProfile}. Carries licenseExpiryDate and
 * commissionPlanId in addition to openapi-distribution.yaml's AgentView schema (agentId, partyId,
 * licenseNumber, licenseStatus, hierarchyParentId) -- both are needed by this module's own tests
 * and by {@link DistributionApi#getApplicablePlan}'s caller-visible precedence, and an additive
 * field on an internal Java record breaks no existing caller. */
public record AgentView(UUID agentId, UUID partyId, String licenseNumber, LicenseStatus licenseStatus,
                         LocalDate licenseExpiryDate, UUID hierarchyParentId, UUID commissionPlanId,
                         /** IFRS 17 I2: the channel this agent sells through and the branch it sells from. */
                         SalesChannel salesChannel, String homeBranch) {}
