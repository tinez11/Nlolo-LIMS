package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.domain.PayoutBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, UUID> {
    Optional<PayoutBatch> findByBatchIdAndTenantId(UUID batchId, UUID tenantId);
}
