package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.TopUpRequest;

import java.util.List;
import java.util.UUID;

public interface TopUpRequestRepository extends JpaRepository<TopUpRequest, UUID> {

    /** A received top-up's reference already counts (ux_top_up_reference, V7). */
    boolean existsByTenantIdAndMethodAndReferenceAndStatusNot(UUID tenantId, String method, String reference, String status);

    List<TopUpRequest> findByPolicyNumberOrderByRequestedAtDesc(String policyNumber);
}
