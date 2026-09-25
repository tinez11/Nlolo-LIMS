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

    /** Finance's work queue for the EFT rail. Ordered oldest-first because the oldest unpaid
     * lender payout is the one that matters most. */
    List<DisbursementInstruction> findByTenantIdAndStatusOrderByCreatedAtAsc(UUID tenantId, String status);

    List<DisbursementInstruction> findByTenantIdAndPurposeAndSourceRefOrderByCreatedAtDesc(UUID tenantId,
                                                                                           String purpose,
                                                                                           String sourceRef);

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

    /**
     * Review fix (Important 3): called only when {@link #resolveTenantByGatewayReference} has
     * already returned {@code null}, to distinguish "nothing matched this reference at all"
     * (NOT_FOUND) from "more than one tenant's row shares it" (AMBIGUOUS) -- the two functions
     * are indistinguishable from that single null return alone, and treating them identically
     * turned a real, working safety mechanism into something that reads exactly like a data-entry
     * error. See db-migrations/payment/V3's Important-3 comment for the full reasoning.
     */
    @Query(value = "SELECT payment.disbursement_gateway_reference_is_ambiguous(:gatewayReference)", nativeQuery = true)
    boolean isGatewayReferenceAmbiguous(@Param("gatewayReference") String gatewayReference);

    /**
     * Review fix (C1): the SECOND tenant-resolution route, keyed on OUR OWN disbursement id rather
     * than the aggregator's gateway_reference. Needed because the rows most likely to require
     * webhook recovery are exactly the ones with {@code gateway_reference IS NULL} -- a transport
     * failure or an ACCEPTED-without-reference response leaves nothing for
     * {@link #resolveTenantByGatewayReference} to key on, so those rows were permanently
     * unreachable by any later callback. The callback's own {@code reference} field carries this id
     * back to us (it is the merchant reference this platform generated and sent to the rail;
     * see PaymentRequestListener's {@code disbursementId.toString()}).
     *
     * <p>Strictly narrower than {@link #resolveTenantByGatewayReference}: this key is a PK
     * component, so cross-tenant ambiguity is structurally impossible and no
     * {@code COUNT(DISTINCT ...)} guard is needed -- see db-migrations/payment/V4's section-2
     * comment for why that difference is a property of the input column, not a missing guard.
     * Same "never widen beyond a tenant id" rule applies as to the gateway_reference resolvers.
     * Returns null when no row carries this id.
     */
    @Query(value = "SELECT payment.resolve_disbursement_tenant_by_id(:disbursementId)", nativeQuery = true)
    UUID resolveTenantByDisbursementId(@Param("disbursementId") UUID disbursementId);
}
