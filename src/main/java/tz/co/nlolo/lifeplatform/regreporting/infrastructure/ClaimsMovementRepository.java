package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimsMovement;
import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimsMovementId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimsMovementRepository extends JpaRepository<ClaimsMovement, ClaimsMovementId> {
    Optional<ClaimsMovement> findByTenantIdAndPeriodAndClaimType(UUID tenantId, String period, String claimType);

    List<ClaimsMovement> findByTenantIdAndPeriod(UUID tenantId, String period);
}
