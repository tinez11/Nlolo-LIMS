package tz.co.nlolo.lifeplatform.claims.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.claims.domain.FuneralClaim;

import java.util.List;
import java.util.UUID;

public interface FuneralClaimRepository extends JpaRepository<FuneralClaim, UUID> {
    /** Every claim naming this covered life -- the one-death-claim-per-life rule's search. */
    List<FuneralClaim> findByTenantIdAndCoveredLifeId(UUID tenantId, UUID coveredLifeId);
}
