package tz.co.nlolo.lifeplatform.policy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.policy.domain.FuneralPolicy;

import java.util.Optional;

public interface FuneralPolicyRepository extends JpaRepository<FuneralPolicy, String> {
    Optional<FuneralPolicy> findByPolicyNumberAndTenantId(String policyNumber, java.util.UUID tenantId);
}
