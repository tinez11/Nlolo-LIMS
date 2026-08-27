package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import tz.co.nlolo.lifeplatform.distribution.domain.AgentProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * M13: the agents list. Until now `distribution` served every per-agent read but
     * no list, so an agent table had no feed at all and the nav item pointed at the
     * onboarding form instead.
     *
     * `q` matches LICENCE NUMBER, not a name: an agent has no name in this module.
     * The person's name lives in `party`, reached through `partyId`, and resolving it
     * here would make `distribution` read another context's data to render a column.
     */
    Page<AgentProfile> findByTenantId(UUID tenantId, Pageable pageable);

    Page<AgentProfile> findByTenantIdAndLicenseStatus(UUID tenantId, LicenseStatus licenseStatus, Pageable pageable);

    @Query("""
        select a from AgentProfile a
        where a.tenantId = :tenantId
          and upper(a.licenseNumber) like upper(concat('%', :q, '%'))
          and (:status is null or a.licenseStatus = :status)
        """)
    Page<AgentProfile> search(@Param("tenantId") UUID tenantId, @Param("q") String q,
                              @Param("status") LicenseStatus status, Pageable pageable);

    /** Task 8's license-expiry sweep: agents whose license is still recorded ACTIVE but whose
     * expiry date has passed. Tenant-scoped like every other query here, per this project's
     * hard tenantId convention -- Task 8 owns how it is invoked across tenants. */
    List<AgentProfile> findByTenantIdAndLicenseStatusAndLicenseExpiryDateLessThanEqual(
        UUID tenantId, LicenseStatus licenseStatus, LocalDate date);
}
