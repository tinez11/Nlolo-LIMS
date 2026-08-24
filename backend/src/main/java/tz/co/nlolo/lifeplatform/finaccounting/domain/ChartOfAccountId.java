package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code @IdClass} for {@link ChartOfAccount}'s composite primary key
 * {@code (tenant_id, account_code)} (finaccounting/V2 section 5). Same shape as
 * {@code reinsurance.domain.PolicyProjectionId} -- a public no-arg constructor (the JPA spec's
 * requirement for an {@code @IdClass}), an all-args constructor, and {@code equals}/{@code
 * hashCode} over every field, copied rather than invented fresh.
 *
 * <p>Field order and names mirror {@link ChartOfAccount}'s {@code @Id} fields exactly:
 * {@code tenantId} then {@code accountCode}.
 */
public class ChartOfAccountId implements Serializable {
    private UUID tenantId;
    private String accountCode;

    public ChartOfAccountId() {}

    public ChartOfAccountId(UUID tenantId, String accountCode) {
        this.tenantId = tenantId;
        this.accountCode = accountCode;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChartOfAccountId that)) return false;
        return Objects.equals(tenantId, that.tenantId) && Objects.equals(accountCode, that.accountCode);
    }

    @Override
    public int hashCode() { return Objects.hash(tenantId, accountCode); }
}
