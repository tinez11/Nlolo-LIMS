package tz.co.nlolo.lifeplatform.billing.infrastructure;

import tz.co.nlolo.lifeplatform.billing.domain.FieldReceipt;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldReceiptRepository extends JpaRepository<FieldReceipt, UUID> {
    Optional<FieldReceipt> findByTenantIdAndClientIdempotencyKey(UUID tenantId, String clientIdempotencyKey);
    // Java-side sweep entry point (Task 5): receipts the SQL sweep already flipped to
    // RECONCILIATION_OVERDUE that the app hasn't published an event for yet, tenant-scoped.
    List<FieldReceipt> findByTenantIdAndStatusAndNotifiedOverdueAtIsNull(UUID tenantId, String status);

    /**
     * The reconciliation queue: one page of this tenant's field receipts, optionally by status.
     *
     * <p>Null-safe on status, the same shape {@code ArrearsCaseRepository.search} uses. The
     * caller supplies the sort and it must be TOTAL -- a bulk sync from one agent lands many
     * receipts with near-identical server timestamps, and paging an order with ties can show one
     * receipt twice and never show another. On unreconciled cash that is money nobody chases.
     */
    @Query("select r from FieldReceipt r where r.tenantId = :tenantId "
        + "and (:status is null or r.status = :status)")
    Page<FieldReceipt> search(@Param("tenantId") UUID tenantId,
                               @Param("status") String status,
                               Pageable pageable);
}
