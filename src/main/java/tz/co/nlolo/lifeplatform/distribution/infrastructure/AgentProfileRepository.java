package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard, per this project's
 * established convention (see any repository in payment or claims). */
public interface AgentProfileRepository extends JpaRepository<AgentProfile, UUID> {
    Optional<AgentProfile> findByAgentIdAndTenantId(UUID agentId, UUID tenantId);
    List<AgentProfile> findByTenantIdAndPartyId(UUID tenantId, UUID partyId);
    List<AgentProfile> findByTenantIdAndHierarchyParentId(UUID tenantId, UUID hierarchyParentId);

    /** Task 8's license-expiry sweep: agents whose license is still recorded ACTIVE but whose
     * expiry date has passed. Tenant-scoped like every other query here, per this project's
     * hard tenantId convention -- Task 8 owns how it is invoked across tenants. */
    List<AgentProfile> findByTenantIdAndLicenseStatusAndLicenseExpiryDateLessThanEqual(
        UUID tenantId, LicenseStatus licenseStatus, LocalDate date);
}
