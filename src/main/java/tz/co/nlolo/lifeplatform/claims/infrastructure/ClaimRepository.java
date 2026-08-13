package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.domain.Claim;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Every method is tenant-scoped -- RLS is the backstop, not the only guard, per this project's
 * established convention (see any repository in payment or policyloan). */
public interface ClaimRepository extends JpaRepository<Claim, UUID> {
    Optional<Claim> findByClaimIdAndTenantId(UUID claimId, UUID tenantId);
    Page<Claim> findByTenantId(UUID tenantId, Pageable pageable);
    Page<Claim> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
    Page<Claim> findByTenantIdAndClaimantPartyId(UUID tenantId, UUID claimantPartyId, Pageable pageable);
    Page<Claim> findByTenantIdAndClaimantPartyIdAndStatus(UUID tenantId, UUID claimantPartyId, String status, Pageable pageable);
    List<Claim> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);

    /** Backs claims/V3's partial unique index on (tenant_id, registration_idempotency_key) --
     * ClaimsApiImpl.registerClaim re-queries this on a caught unique-constraint violation to
     * return the EXISTING claim instead of erroring on a repeated registration attempt. */
    Optional<Claim> findByTenantIdAndRegistrationIdempotencyKey(UUID tenantId, String registrationIdempotencyKey);
}
