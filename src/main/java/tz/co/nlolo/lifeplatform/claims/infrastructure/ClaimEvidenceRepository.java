package tz.co.nlolo.lifeplatform.claims.infrastructure;

import tz.co.nlolo.lifeplatform.claims.domain.ClaimEvidence;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ClaimEvidenceRepository extends JpaRepository<ClaimEvidence, UUID> {
    List<ClaimEvidence> findByClaimIdAndTenantIdOrderByUploadedAtDesc(UUID claimId, UUID tenantId);
}
