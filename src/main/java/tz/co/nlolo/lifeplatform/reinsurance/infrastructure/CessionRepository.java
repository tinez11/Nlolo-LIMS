package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.Cession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CessionRepository extends JpaRepository<Cession, UUID> {
    List<Cession> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
    boolean existsByTenantIdAndPolicyNumberAndTreatyId(UUID tenantId, String policyNumber, UUID treatyId);
}
