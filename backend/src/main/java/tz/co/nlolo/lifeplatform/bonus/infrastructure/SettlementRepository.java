package tz.co.nlolo.lifeplatform.bonus.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.bonus.domain.Settlement;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {
    Optional<Settlement> findByExitTypeAndExitRef(String exitType, String exitRef);
    List<Settlement> findByPolicyNumberOrderByRecordedAtDesc(String policyNumber);
}
