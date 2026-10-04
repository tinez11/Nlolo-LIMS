package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.nlolo.lifeplatform.policy.domain.CoveredLife;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoveredLifeRepository extends JpaRepository<CoveredLife, UUID> {
    /** Main member first, then the order the lives were added. */
    @Query("SELECT l FROM CoveredLife l WHERE l.tenantId = :tenantId AND l.policyNumber = :policyNumber"
        + " ORDER BY CASE WHEN l.role = 'MAIN_MEMBER' THEN 0 ELSE 1 END, l.createdAt, l.coveredLifeId")
    List<CoveredLife> findByPolicy(@Param("tenantId") UUID tenantId, @Param("policyNumber") String policyNumber);

    Optional<CoveredLife> findByCoveredLifeIdAndTenantId(UUID coveredLifeId, UUID tenantId);

    /** [policy_number, tenant_id] of every funeral policy with a life still active, across tenants (policy V35). */
    @Query(value = "SELECT policy_number, tenant_id FROM policy.funeral_policies_with_active_lives()", nativeQuery = true)
    List<Object[]> policiesWithActiveLives();
}
