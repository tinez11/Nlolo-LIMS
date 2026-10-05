package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** An Idempotency-Key already answered on a request that creates something (U2: a top-up), and what it created. */
@Entity(name = "UnitLinkedRequestKey")
@Table(name = "request_key", schema = "unitlinked")
@IdClass(RequestKey.Id.class)
public class RequestKey {

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

    protected RequestKey() {}

    public RequestKey(UUID tenantId, String idempotencyKey, String operation, String target, UUID resourceId,
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
