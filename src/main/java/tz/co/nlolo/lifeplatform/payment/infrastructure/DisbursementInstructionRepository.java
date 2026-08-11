package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisbursementInstructionRepository
        extends JpaRepository<DisbursementInstruction, DisbursementInstruction.DisbursementInstructionId> {
    Optional<DisbursementInstruction> findByDisbursementIdAndTenantId(UUID disbursementId, UUID tenantId);
    Optional<DisbursementInstruction> findByIdempotencyKeyAndTenantId(String idempotencyKey, UUID tenantId);
    Optional<DisbursementInstruction> findByGatewayReferenceAndTenantId(String gatewayReference, UUID tenantId);
    List<DisbursementInstruction> findByBatchIdAndTenantId(UUID batchId, UUID tenantId);
}
