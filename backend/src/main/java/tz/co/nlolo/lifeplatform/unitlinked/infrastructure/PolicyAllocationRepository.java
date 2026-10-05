package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PolicyAllocation;

import java.util.List;
import java.util.UUID;

/** Every finder takes the tenant: tests connect as the table owner, so RLS alone would not scope them. */
public interface PolicyAllocationRepository extends JpaRepository<PolicyAllocation, UUID> {

    List<PolicyAllocation> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);

    boolean existsByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
