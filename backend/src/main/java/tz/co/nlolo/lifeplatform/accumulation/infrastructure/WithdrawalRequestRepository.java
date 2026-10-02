package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.WithdrawalRequest;

import java.util.List;
import java.util.UUID;

public interface WithdrawalRequestRepository extends JpaRepository<WithdrawalRequest, UUID> {
    List<WithdrawalRequest> findByPolicyNumberOrderByRequestedAtDesc(String policyNumber);
    boolean existsByPolicyNumberAndStatusIn(String policyNumber, List<String> statuses);
}
