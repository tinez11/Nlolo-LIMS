package tz.co.nlolo.lifeplatform.reinsurance.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link PolicyProjection}'s composite primary key
 * {@code (tenant_id, policy_number)} (reinsurance/V2 section 11). Same shape as
 * {@code payment.domain.DisbursementInstruction.DisbursementInstructionId} -- a public no-arg
 * constructor (the JPA spec's requirement for an {@code @IdClass}), an all-args constructor, and
 * {@code equals}/{@code hashCode} over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link PolicyProjection}'s {@code @Id} fields exactly:
 * {@code tenantId} then {@code policyNumber}.
 */
public class PolicyProjectionId implements Serializable {
    private UUID tenantId;
    private String policyNumber;

    public PolicyProjectionId() {}

    public PolicyProjectionId(UUID tenantId, String policyNumber) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PolicyProjectionId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(policyNumber, that.policyNumber);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, policyNumber); }
}
