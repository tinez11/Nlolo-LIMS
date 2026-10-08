package tz.co.nlolo.lifeplatform.claims.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.claims.domain.ClaimDocumentRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimDocumentRequestRepository extends JpaRepository<ClaimDocumentRequest, UUID> {

    List<ClaimDocumentRequest> findByTenantIdAndClaimIdOrderByRequestedAtAsc(UUID tenantId, UUID claimId);

    Optional<ClaimDocumentRequest> findByTenantIdAndRequestId(UUID tenantId, UUID requestId);
}
