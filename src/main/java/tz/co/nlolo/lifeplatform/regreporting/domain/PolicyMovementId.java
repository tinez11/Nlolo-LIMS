package tz.co.nlolo.lifeplatform.regreporting.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link PolicyMovement}'s composite primary key
 * {@code (tenant_id, period, product_id)} (regreporting/V2 section 4). Same shape as
 * {@code reinsurance.domain.PolicyProjectionId} -- a public no-arg constructor (the JPA spec's
 * requirement for an {@code @IdClass}), an all-args constructor, and {@code equals}/{@code hashCode}
 * over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link PolicyMovement}'s {@code @Id} fields exactly:
 * {@code tenantId}, {@code period}, then {@code productId}.
 */
public class PolicyMovementId implements Serializable {
    private UUID tenantId;
    private String period;
    private UUID productId;

    public PolicyMovementId() {}

    public PolicyMovementId(UUID tenantId, String period, UUID productId) {
        this.tenantId = tenantId;
        this.period = period;
        this.productId = productId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PolicyMovementId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(period, that.period)
            && Objects.equals(productId, that.productId);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, period, productId); }
}
