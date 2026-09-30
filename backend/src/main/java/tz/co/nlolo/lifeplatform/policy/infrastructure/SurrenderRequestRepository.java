package tz.co.nlolo.lifeplatform.policy.infrastructure;

import tz.co.nlolo.lifeplatform.policy.domain.SurrenderRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SurrenderRequestRepository extends JpaRepository<SurrenderRequest, UUID> {

    Optional<SurrenderRequest> findBySurrenderRequestIdAndTenantId(UUID surrenderRequestId, UUID tenantId);

    List<SurrenderRequest> findByPolicyNumberAndTenantIdAndStatusIn(String policyNumber, UUID tenantId, List<String> statuses);

    /** The one open request for a policy, if any -- REQUESTED or APPROVED. The partial unique index
     *  guarantees at most one, so callers take the first. */
    default Optional<SurrenderRequest> findLive(String policyNumber, UUID tenantId) {
        return findByPolicyNumberAndTenantIdAndStatusIn(policyNumber, tenantId, List.of("REQUESTED", "APPROVED"))
            .stream().findFirst();
    }
}
