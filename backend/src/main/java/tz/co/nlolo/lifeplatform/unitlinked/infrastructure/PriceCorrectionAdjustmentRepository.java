package tz.co.nlolo.lifeplatform.unitlinked.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PriceCorrectionAdjustment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PriceCorrectionAdjustmentRepository extends JpaRepository<PriceCorrectionAdjustment, UUID> {

    Optional<PriceCorrectionAdjustment> findByTenantIdAndAdjustmentId(UUID tenantId, UUID adjustmentId);

    List<PriceCorrectionAdjustment> findByTenantIdAndStatusOrderByPolicyNumber(UUID tenantId, String status);

    List<PriceCorrectionAdjustment> findByTenantIdOrderByPolicyNumber(UUID tenantId);
}
