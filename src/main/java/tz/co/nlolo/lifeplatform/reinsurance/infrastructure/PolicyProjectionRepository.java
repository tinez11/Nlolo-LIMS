package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjection;
import tz.co.nlolo.lifeplatform.reinsurance.domain.PolicyProjectionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyProjectionRepository extends JpaRepository<PolicyProjection, PolicyProjectionId> {
    Optional<PolicyProjection> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
