package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.AdjustmentRequest;

import java.util.List;
import java.util.UUID;

public interface AdjustmentRequestRepository extends JpaRepository<AdjustmentRequest, UUID> {
    List<AdjustmentRequest> findByPolicyNumberOrderByProposedAtDesc(String policyNumber);
}
