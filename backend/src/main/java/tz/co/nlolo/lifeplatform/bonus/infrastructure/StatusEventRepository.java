package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.bonus.domain.StatusEvent;

import java.util.List;
import java.util.UUID;

public interface StatusEventRepository extends JpaRepository<StatusEvent, UUID> {
    boolean existsByTenantIdAndEventId(UUID tenantId, UUID eventId);
    List<StatusEvent> findByPolicyNumberOrderByEffectiveAtAsc(String policyNumber);
}
