package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.ReinsuranceMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.ReinsuranceMovementId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ReinsuranceMovementRepository extends JpaRepository<ReinsuranceMovement, ReinsuranceMovementId> {
    Optional<ReinsuranceMovement> findByTenantIdAndPeriod(UUID tenantId, String period);
}
