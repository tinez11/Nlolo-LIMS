package tz.co.nlolo.lifeplatform.bonus.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An Idempotency-Key already answered, and what it created. Insert-only. accumulation's RequestKey,
 * for bonus -- named apart from it, because JPA entity names and Spring repository bean names are
 * global: two classes both called RequestKey fail application startup.
 */
@Entity
@Table(name = "request_key", schema = "bonus")
@IdClass(BonusRequestKey.Id.class)
public class BonusRequestKey {

    public static class Id implements Serializable {
        private UUID tenantId;
        private String idempotencyKey;

        protected Id() {}

        public Id(UUID tenantId, String idempotencyKey) {
            this.tenantId = tenantId;
            this.idempotencyKey = idempotencyKey;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Id other && Objects.equals(tenantId, other.tenantId)
                && Objects.equals(idempotencyKey, other.idempotencyKey);
        }

        @Override
        public int hashCode() { return Objects.hash(tenantId, idempotencyKey); }
    }

    @jakarta.persistence.Id @Column(name = "tenant_id") private UUID tenantId;
    @jakarta.persistence.Id @Column(name = "idempotency_key") private String idempotencyKey;
    @Column(nullable = false) private String operation;
    @Column(nullable = false) private String target;
    @Column(name = "resource_id", nullable = false) private UUID resourceId;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected BonusRequestKey() {}

    public BonusRequestKey(UUID tenantId, String idempotencyKey, String operation, String target, UUID resourceId,
                      String createdBy) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.target = target;
        this.resourceId = resourceId;
        this.createdBy = createdBy;
    }

    public String getOperation() { return operation; }
    public String getTarget() { return target; }
    public UUID getResourceId() { return resourceId; }
}
