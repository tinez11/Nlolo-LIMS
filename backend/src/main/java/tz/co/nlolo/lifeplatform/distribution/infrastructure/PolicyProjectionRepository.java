package tz.co.nlolo.lifeplatform.distribution.infrastructure;

import tz.co.nlolo.lifeplatform.distribution.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.distribution.domain.PolicyProjectionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard. */
public interface PolicyProjectionRepository extends JpaRepository<PolicyProjection, PolicyProjectionId> {
    Optional<PolicyProjection> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
