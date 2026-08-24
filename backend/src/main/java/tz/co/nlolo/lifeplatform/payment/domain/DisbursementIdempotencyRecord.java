package tz.co.nlolo.lifeplatform.payment.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The non-partitioned dedup gate for outbound disbursements. docs/06-database-schema.md:12
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
@Table(name = "disbursement_idempotency_registry", schema = "payment")
@IdClass(DisbursementIdempotencyRecord.DisbursementIdempotencyId.class)
public class DisbursementIdempotencyRecord {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    @Column(name = "disbursement_id", nullable = false)
    private UUID disbursementId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected DisbursementIdempotencyRecord() {}

    public DisbursementIdempotencyRecord(UUID tenantId, String idempotencyKey, UUID disbursementId) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.disbursementId = disbursementId;
    }

    public UUID getTenantId() { return tenantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getDisbursementId() { return disbursementId; }

    public static class DisbursementIdempotencyId implements Serializable {
        private UUID tenantId;
        private String idempotencyKey;

        public DisbursementIdempotencyId() {}
        public DisbursementIdempotencyId(UUID tenantId, String idempotencyKey) {
            this.tenantId = tenantId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DisbursementIdempotencyId that)) return false;
            return Objects.equals(tenantId, that.tenantId) && Objects.equals(idempotencyKey, that.idempotencyKey);
        }

        @Override
        public int hashCode() { return Objects.hash(tenantId, idempotencyKey); }
    }
}
