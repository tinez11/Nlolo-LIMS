package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.ExitState;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExitStateRepository extends JpaRepository<ExitState, UUID> {

    Optional<ExitState> findByTenantIdAndSourceTypeAndSourceRef(UUID tenantId, String sourceType, String sourceRef);

    List<ExitState> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);

    List<ExitState> findByTenantIdAndStatus(UUID tenantId, String status);
}
