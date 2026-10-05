package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FrozenPolicy;

import java.util.Optional;
import java.util.UUID;

public interface FrozenPolicyRepository extends JpaRepository<FrozenPolicy, String> {

    Optional<FrozenPolicy> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);

    boolean existsByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
