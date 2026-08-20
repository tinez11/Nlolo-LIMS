package tz.co.nlolo.lifeplatform.regreporting.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link ReturnDefinitionLine}'s composite primary key
 * {@code (tenant_id, return_type, line_no)} (regreporting/V2 section 7). Same shape as
 * {@code reinsurance.domain.PolicyProjectionId} -- a public no-arg constructor (the JPA spec's
 * requirement for an {@code @IdClass}), an all-args constructor, and {@code equals}/{@code hashCode}
 * over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link ReturnDefinitionLine}'s {@code @Id} fields exactly:
 * {@code tenantId}, {@code returnType}, then {@code lineNo}.
 */
public class ReturnDefinitionLineId implements Serializable {
    private UUID tenantId;
    private String returnType;
    private int lineNo;

    public ReturnDefinitionLineId() {}

    public ReturnDefinitionLineId(UUID tenantId, String returnType, int lineNo) {
        this.tenantId = tenantId;
        this.returnType = returnType;
        this.lineNo = lineNo;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ReturnDefinitionLineId that)) return false;
        return lineNo == that.lineNo && Objects.equals(tenantId, that.tenantId)
            && Objects.equals(returnType, that.returnType);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, returnType, lineNo); }
}
