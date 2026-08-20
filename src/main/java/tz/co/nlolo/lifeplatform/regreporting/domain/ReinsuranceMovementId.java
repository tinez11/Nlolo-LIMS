package tz.co.nlolo.lifeplatform.regreporting.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link ReinsuranceMovement}'s composite primary key
 * {@code (tenant_id, period)} (regreporting/V2 section 6). Same shape as
 * {@code reinsurance.domain.PolicyProjectionId} -- a public no-arg constructor (the JPA spec's
 * requirement for an {@code @IdClass}), an all-args constructor, and {@code equals}/{@code hashCode}
 * over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link ReinsuranceMovement}'s {@code @Id} fields exactly:
 * {@code tenantId} then {@code period}. Deliberately not attributed by product -- see V2 section 6's
 * comment on why keying by product would race against reinsurance's own CessionRecorded ordering.
 */
public class ReinsuranceMovementId implements Serializable {
    private UUID tenantId;
    private String period;

    public ReinsuranceMovementId() {}

    public ReinsuranceMovementId(UUID tenantId, String period) {
        this.tenantId = tenantId;
        this.period = period;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReinsuranceMovementId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(period, that.period);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, period); }
}
