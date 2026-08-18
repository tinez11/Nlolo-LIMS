package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRecoveryRepository extends JpaRepository<ClaimRecovery, UUID> {
    Optional<ClaimRecovery> findByRecoveryIdAndTenantId(UUID recoveryId, UUID tenantId);
    List<ClaimRecovery> findByTenantIdAndClaimId(UUID tenantId, UUID claimId);
    boolean existsByTenantIdAndClaimIdAndTreatyId(UUID tenantId, UUID claimId, UUID treatyId);
}
