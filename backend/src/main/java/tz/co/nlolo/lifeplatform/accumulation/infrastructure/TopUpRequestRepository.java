package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.TopUpRequest;

import java.util.List;
import java.util.UUID;

public interface TopUpRequestRepository extends JpaRepository<TopUpRequest, UUID> {
    List<TopUpRequest> findByPolicyNumberOrderByRequestedAtDesc(String policyNumber);
}
