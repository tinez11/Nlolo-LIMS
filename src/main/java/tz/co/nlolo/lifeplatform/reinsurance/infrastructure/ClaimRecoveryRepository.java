package tz.co.nlolo.lifeplatform.reinsurance.infrastructure;

import tz.co.nlolo.lifeplatform.reinsurance.domain.ClaimRecovery;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimRecoveryRepository extends JpaRepository<ClaimRecovery, UUID> {
    Optional<ClaimRecovery> findByRecoveryIdAndTenantId(UUID recoveryId, UUID tenantId);
    List<ClaimRecovery> findByTenantIdAndClaimId(UUID tenantId, UUID claimId);

    /** Keyed on (tenant, claim) only, matching {@code ux_recovery_once} (reinsurance/V2 section 10)
     * -- the design is one treaty per policy, so "a recovery already exists for this claim" is the
     * real idempotency check, not "for this (claim, treaty) pair". */
    boolean existsByTenantIdAndClaimId(UUID tenantId, UUID claimId);
}
