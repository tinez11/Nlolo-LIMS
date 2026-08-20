package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyDimension;
import tz.co.nlolo.lifeplatform.regreporting.domain.PolicyDimensionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyDimensionRepository extends JpaRepository<PolicyDimension, PolicyDimensionId> {
    Optional<PolicyDimension> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
