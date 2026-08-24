package tz.co.nlolo.lifeplatform.regreporting.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link ClaimDimension}'s composite primary key
 * {@code (tenant_id, claim_id)} (regreporting/V2 section 5). Same shape as
 * {@code reinsurance.domain.PolicyProjectionId} -- a public no-arg constructor (the JPA spec's
 * requirement for an {@code @IdClass}), an all-args constructor, and {@code equals}/{@code hashCode}
 * over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link ClaimDimension}'s {@code @Id} fields exactly:
 * {@code tenantId} then {@code claimId}.
 */
public class ClaimDimensionId implements Serializable {
    private UUID tenantId;
    private UUID claimId;

    public ClaimDimensionId() {}

    public ClaimDimensionId(UUID tenantId, UUID claimId) {
        this.tenantId = tenantId;
        this.claimId = claimId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ClaimDimensionId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(claimId, that.claimId);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, claimId); }
}
