package tz.co.nlolo.lifeplatform.claims.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.claims.domain.ClaimDocumentRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClaimDocumentRequestRepository extends JpaRepository<ClaimDocumentRequest, UUID> {

    List<ClaimDocumentRequest> findByTenantIdAndClaimIdOrderByRequestedAtAsc(UUID tenantId, UUID claimId);

    Optional<ClaimDocumentRequest> findByTenantIdAndRequestId(UUID tenantId, UUID requestId);

    /**
     * Requests still outstanding across every claim in the tenant (2026-10-15) -- the question the
     * per-claim reads could not answer, and the claims dashboard's "waiting on a document" count.
     *
     * <p>OPEN only: a RECEIVED request has its evidence and a WITHDRAWN one was taken back, and
     * neither is work anybody is waiting on.
     */
    long countByTenantIdAndStatus(UUID tenantId, String status);
}
