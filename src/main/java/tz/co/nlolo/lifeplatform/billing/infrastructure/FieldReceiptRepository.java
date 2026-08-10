package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.domain.FieldReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldReceiptRepository extends JpaRepository<FieldReceipt, UUID> {
    Optional<FieldReceipt> findByTenantIdAndClientIdempotencyKey(UUID tenantId, String clientIdempotencyKey);
    // Java-side sweep entry point (Task 5): receipts the SQL sweep already flipped to
    // RECONCILIATION_OVERDUE that the app hasn't published an event for yet, tenant-scoped.
    List<FieldReceipt> findByTenantIdAndStatusAndNotifiedOverdueAtIsNull(UUID tenantId, String status);
}
