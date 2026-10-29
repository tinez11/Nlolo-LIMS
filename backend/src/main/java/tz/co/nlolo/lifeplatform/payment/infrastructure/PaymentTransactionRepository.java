package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository
        extends JpaRepository<PaymentTransaction, PaymentTransaction.PaymentTransactionId> {
    Optional<PaymentTransaction> findByPaymentTransactionIdAndTenantId(UUID paymentTransactionId, UUID tenantId);
    Optional<PaymentTransaction> findByIdempotencyKeyAndTenantId(String idempotencyKey, UUID tenantId);

    /** One invoice's receipts in one state -- those still waiting for their second signature (2026-10-28). */
    java.util.List<PaymentTransaction> findByTenantIdAndSourceRefAndStatusOrderByCreatedAtAsc(UUID tenantId, String sourceRef,
                                                                                            String status);
    Optional<PaymentTransaction> findByGatewayReferenceAndTenantId(String gatewayReference, UUID tenantId);

    // ---- Recorded receipts (2026-10-27) ----

    /** Whether this method's reference -- a bank slip, a cheque, a Lipa transaction -- is already on a live receipt. */
    boolean existsByTenantIdAndMethodAndGatewayReferenceAndStatusNot(UUID tenantId, String method, String gatewayReference,
                                                                    String status);

    /** Whether an agent's field receipt is already recorded on a live receipt. */
    boolean existsByTenantIdAndFieldReceiptIdAndStatusNot(UUID tenantId, UUID fieldReceiptId, String status);

    java.util.List<PaymentTransaction> findByTenantIdAndStatusOrderByCreatedAtAsc(UUID tenantId, String status);

    /** Same bootstrap as DisbursementInstructionRepository.resolveTenantByGatewayReference, for
     * the collection (inbound-payment) ledger -- see that method's javadoc and
     * db-migrations/payment/V3 for why this needs a SECURITY DEFINER function rather than a
     * plain unscoped SELECT. */
    @Query(value = "SELECT payment.resolve_payment_transaction_tenant(:gatewayReference)", nativeQuery = true)
    UUID resolveTenantByGatewayReference(@Param("gatewayReference") String gatewayReference);

    /** Same "distinguish AMBIGUOUS from NOT_FOUND" bootstrap as
     * DisbursementInstructionRepository.isGatewayReferenceAmbiguous, for the collection ledger. */
    @Query(value = "SELECT payment.payment_transaction_gateway_reference_is_ambiguous(:gatewayReference)", nativeQuery = true)
    boolean isGatewayReferenceAmbiguous(@Param("gatewayReference") String gatewayReference);

    /** Review fix (C1): the id-keyed tenant-resolution route for the collection ledger -- same
     * purpose, same narrowness argument and same "no COUNT(DISTINCT) guard needed because the key
     * is a PK component" reasoning as
     * {@code DisbursementInstructionRepository.resolveTenantByDisbursementId}. See that method's
     * javadoc and db-migrations/payment/V4's section-2 comment. */
    @Query(value = "SELECT payment.resolve_payment_transaction_tenant_by_id(:paymentTransactionId)", nativeQuery = true)
    UUID resolveTenantByPaymentTransactionId(@Param("paymentTransactionId") UUID paymentTransactionId);
}
