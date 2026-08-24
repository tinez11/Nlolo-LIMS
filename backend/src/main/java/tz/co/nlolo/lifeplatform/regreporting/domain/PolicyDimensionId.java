package tz.co.nlolo.lifeplatform.regreporting.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link PolicyDimension}'s composite primary key
 * {@code (tenant_id, policy_number)} (regreporting/V2 section 5). Same shape as
 * {@code reinsurance.domain.PolicyProjectionId} -- a public no-arg constructor (the JPA spec's
 * requirement for an {@code @IdClass}), an all-args constructor, and {@code equals}/{@code hashCode}
 * over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link PolicyDimension}'s {@code @Id} fields exactly:
 * {@code tenantId} then {@code policyNumber}.
 */
public class PolicyDimensionId implements Serializable {
    private UUID tenantId;
    private String policyNumber;

    public PolicyDimensionId() {}

    public PolicyDimensionId(UUID tenantId, String policyNumber) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PolicyDimensionId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(policyNumber, that.policyNumber);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, policyNumber); }
}
