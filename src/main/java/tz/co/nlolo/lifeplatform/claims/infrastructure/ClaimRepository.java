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
    Page<Claim> findByTenantIdAndStatus(UUID tenantId, String status, Pageable pageable);
    Page<Claim> findByTenantIdAndClaimantPartyId(UUID tenantId, UUID claimantPartyId, Pageable pageable);
    List<Claim> findByTenantIdAndPolicyNumber(UUID tenantId, String policyNumber);
}
