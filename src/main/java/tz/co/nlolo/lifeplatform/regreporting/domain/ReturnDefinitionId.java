package tz.co.nlolo.lifeplatform.regreporting.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link ReturnDefinition}'s composite primary key
 * {@code (tenant_id, return_type)} (regreporting/V2 section 7). Same shape as
 * {@code reinsurance.domain.PolicyProjectionId} -- a public no-arg constructor (the JPA spec's
 * requirement for an {@code @IdClass}), an all-args constructor, and {@code equals}/{@code hashCode}
 * over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link ReturnDefinition}'s {@code @Id} fields exactly:
 * {@code tenantId} then {@code returnType}.
 */
public class ReturnDefinitionId implements Serializable {
    private UUID tenantId;
    private String returnType;

    public ReturnDefinitionId() {}

    public ReturnDefinitionId(UUID tenantId, String returnType) {
        this.tenantId = tenantId;
        this.returnType = returnType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReturnDefinitionId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(returnType, that.returnType);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, returnType); }
}
