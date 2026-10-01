package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.accumulation.domain.TransferIn;

import java.util.List;
import java.util.UUID;

public interface TransferInRepository extends JpaRepository<TransferIn, UUID> {
    List<TransferIn> findByPolicyNumberOrderByRecordedAtDesc(String policyNumber);
}
