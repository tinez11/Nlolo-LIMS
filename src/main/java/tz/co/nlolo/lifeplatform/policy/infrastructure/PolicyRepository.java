package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.Policy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PolicyRepository extends JpaRepository<Policy, String> {
    Optional<Policy> findByPolicyNumberAndTenantId(String policyNumber, UUID tenantId);
    Page<Policy> findByTenantIdAndPolicyholderPartyId(UUID tenantId, UUID policyholderPartyId, Pageable pageable);
    Page<Policy> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
    Page<Policy> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Policy> findByTenantIdAndPolicyholderPartyIdAndStatus(UUID tenantId, UUID policyholderPartyId, String status, Pageable pageable);
}
