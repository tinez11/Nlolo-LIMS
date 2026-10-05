package tz.co.nlolo.lifeplatform.finaccounting.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite key of {@link AccountingPeriod}: one row per tenant and period. */
public class AccountingPeriodId implements Serializable {

    private UUID tenantId;
    private String period;

    protected AccountingPeriodId() {}

    public AccountingPeriodId(UUID tenantId, String period) {
        this.tenantId = tenantId;
        this.period = period;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof AccountingPeriodId other && Objects.equals(tenantId, other.tenantId)
            && Objects.equals(period, other.period);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tenantId, period);
    }
}
