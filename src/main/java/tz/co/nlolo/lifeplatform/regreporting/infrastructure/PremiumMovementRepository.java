package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.PremiumMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.PremiumMovementId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PremiumMovementRepository extends JpaRepository<PremiumMovement, PremiumMovementId> {
    Optional<PremiumMovement> findByTenantIdAndPeriodAndProductId(UUID tenantId, String period, UUID productId);

    List<PremiumMovement> findByTenantIdAndPeriod(UUID tenantId, String period);
}
