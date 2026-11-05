package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A policy's balance (billing V14, money movement step 2): the row the allocator locks so two deposits at once cannot
 * pay the same invoice twice. The amount is not stored here -- it is the sum of the policy's {@link BalanceEntry} rows,
 * which are add-only, so the statement and the balance can never disagree.
 */
@Entity
@Table(name = "policy_balance", schema = "billing")
@IdClass(PolicyBalance.Key.class)
public class PolicyBalance {

    @Id @Column(name = "tenant_id") private UUID tenantId;
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(nullable = false) private String currency;
    @Version private long version;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected PolicyBalance() {}

    public PolicyBalance(UUID tenantId, String policyNumber, String currency) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.currency = currency;
    }

    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getCurrency() { return currency; }

    public static class Key implements Serializable {
        private UUID tenantId;
        private String policyNumber;

        public Key() {}
        public Key(UUID tenantId, String policyNumber) { this.tenantId = tenantId; this.policyNumber = policyNumber; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(tenantId, k.tenantId) && Objects.equals(policyNumber, k.policyNumber);
        }

        @Override
        public int hashCode() { return Objects.hash(tenantId, policyNumber); }
    }
}
