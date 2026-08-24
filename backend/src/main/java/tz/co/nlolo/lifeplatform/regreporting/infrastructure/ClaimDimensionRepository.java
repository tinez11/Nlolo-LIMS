package tz.co.nlolo.lifeplatform.regreporting.infrastructure;

import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimDimension;
import tz.co.nlolo.lifeplatform.regreporting.domain.ClaimDimensionId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ClaimDimensionRepository extends JpaRepository<ClaimDimension, ClaimDimensionId> {
    Optional<ClaimDimension> findByTenantIdAndClaimId(UUID tenantId, UUID claimId);
}
