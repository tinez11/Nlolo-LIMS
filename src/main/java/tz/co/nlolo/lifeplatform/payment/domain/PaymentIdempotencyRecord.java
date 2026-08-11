package tz.co.nlolo.lifeplatform.payment.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The non-partitioned dedup gate for inbound payment transactions. docs/06-database-schema.md:12
 * explains why this table exists at all rather than a unique index on the partitioned ledger:
 * Postgres requires a partitioned table's unique constraint to include the partition key, and
 * widening it to (idempotency_key, created_at) "would have 'fixed' the error message but
 * silently broken the actual guarantee". Do not attempt to fold this back into the ledger.
 *
 * <p>Written via an INSERT ... ON CONFLICT DO NOTHING whose affected-row count is the dedup
 * decision (see PaymentApiImpl) -- never a check-then-insert, which has a real TOCTOU window
 * under concurrent duplicate delivery.
 */
@Entity
@Table(name = "payment_transaction_idempotency_registry", schema = "payment")
@IdClass(PaymentIdempotencyRecord.PaymentIdempotencyId.class)
public class PaymentIdempotencyRecord {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "payment_transaction_id", nullable = false)
    private UUID paymentTransactionId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PaymentIdempotencyRecord() {}

    public PaymentIdempotencyRecord(UUID tenantId, String idempotencyKey, UUID paymentTransactionId) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.paymentTransactionId = paymentTransactionId;
    }

    public UUID getTenantId() { return tenantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getPaymentTransactionId() { return paymentTransactionId; }

    public static class PaymentIdempotencyId implements Serializable {
        private UUID tenantId;
        private String idempotencyKey;

        public PaymentIdempotencyId() {}
        public PaymentIdempotencyId(UUID tenantId, String idempotencyKey) {
            this.tenantId = tenantId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PaymentIdempotencyId that)) return false;
            return Objects.equals(tenantId, that.tenantId) && Objects.equals(idempotencyKey, that.idempotencyKey);
        }

        @Override
        public int hashCode() { return Objects.hash(tenantId, idempotencyKey); }
    }
}
