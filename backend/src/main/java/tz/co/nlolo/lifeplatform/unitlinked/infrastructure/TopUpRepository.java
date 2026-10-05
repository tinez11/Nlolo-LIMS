package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.TopUp;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TopUpRepository extends JpaRepository<TopUp, UUID> {

    Optional<TopUp> findByTenantIdAndTopUpId(UUID tenantId, UUID topUpId);

    List<TopUp> findByTenantIdAndPolicyNumberOrderByRequestedAtDesc(UUID tenantId, String policyNumber);
}
