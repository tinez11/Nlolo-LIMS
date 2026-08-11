package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.domain.DisbursementInstruction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisbursementInstructionRepository
        extends JpaRepository<DisbursementInstruction, DisbursementInstruction.DisbursementInstructionId> {
    Optional<DisbursementInstruction> findByDisbursementIdAndTenantId(UUID disbursementId, UUID tenantId);
    Optional<DisbursementInstruction> findByIdempotencyKeyAndTenantId(String idempotencyKey, UUID tenantId);
    Optional<DisbursementInstruction> findByGatewayReferenceAndTenantId(String gatewayReference, UUID tenantId);
    List<DisbursementInstruction> findByBatchIdAndTenantId(UUID batchId, UUID tenantId);

    /**
     * Task 8's inbound callback bootstrap: resolves ONLY the owning tenant_id for a given
     * gateway reference, before any TenantContext exists to scope an ordinary query with. Calls
     * the SECURITY DEFINER function db-migrations/payment/V3 adds specifically for this -- see
     * that migration's header comment for why an ordinary unscoped SELECT cannot work here
     * (payment's RLS policies are fail-closed: an unset app.current_tenant_id makes every row
     * invisible, not just unfiltered). Returns null when no row carries this gateway reference.
     * Never widen this to return more than a tenant id -- the whole point is a narrow, PK-style
     * bootstrap, not a general-purpose cross-tenant finder.
     */
    @Query(value = "SELECT payment.resolve_disbursement_tenant(:gatewayReference)", nativeQuery = true)
    UUID resolveTenantByGatewayReference(@Param("gatewayReference") String gatewayReference);
}
