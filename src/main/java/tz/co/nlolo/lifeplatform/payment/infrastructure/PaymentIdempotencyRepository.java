package tz.co.nlolo.lifeplatform.payment.infrastructure;

import tz.co.nlolo.lifeplatform.payment.domain.PaymentIdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface PaymentIdempotencyRepository
        extends JpaRepository<PaymentIdempotencyRecord, PaymentIdempotencyRecord.PaymentIdempotencyId> {

    /**
     * Returns 1 if this (tenant, key) pair was newly claimed, 0 if it was already present --
     * i.e. 0 means "already processed, drop this event as a safe duplicate". Native query
     * because ON CONFLICT DO NOTHING is Postgres-specific and JPQL cannot express it, and
     * because the affected-row count IS the dedup decision. clearAutomatically/flushAutomatically
     * keep the persistence context consistent with the row this statement writes behind its back.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "INSERT INTO payment.payment_transaction_idempotency_registry (tenant_id, idempotency_key, payment_transaction_id) "
                 + "VALUES (:tenantId, :idempotencyKey, :paymentTransactionId) ON CONFLICT DO NOTHING", nativeQuery = true)
    int claimKey(@Param("tenantId") UUID tenantId, @Param("idempotencyKey") String idempotencyKey,
                 @Param("paymentTransactionId") UUID paymentTransactionId);
}
