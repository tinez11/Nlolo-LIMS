package tz.co.nlolo.lifeplatform.distribution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A borrower's premium refunded before their lender paid the file (distribution V9, 2026-10-30). The file's commission
 * was not booked yet, so there was nothing to claw back from; the refund is remembered and reversed from the
 * commission the moment it is booked. One per borrower per file, so a redelivered refund is a no-op.
 */
@Entity
@Table(name = "credit_life_early_refund", schema = "distribution")
@IdClass(CreditLifeEarlyRefund.Key.class)
public class CreditLifeEarlyRefund {

    @Id
    @Column(name = "submission_id")
    private UUID submissionId;

    @Id
    @Column(name = "policy_member_id")
    private UUID policyMemberId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private BigDecimal refunded;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected CreditLifeEarlyRefund() {}

    public CreditLifeEarlyRefund(UUID tenantId, UUID submissionId, UUID policyMemberId, BigDecimal refunded) {
        this.tenantId = tenantId;
        this.submissionId = submissionId;
        this.policyMemberId = policyMemberId;
        this.refunded = refunded;
    }

    public UUID getSubmissionId() { return submissionId; }
    public UUID getPolicyMemberId() { return policyMemberId; }
    public BigDecimal getRefunded() { return refunded; }

    public static class Key implements Serializable {
        private UUID submissionId;
        private UUID policyMemberId;

        public Key() {}

        public Key(UUID submissionId, UUID policyMemberId) {
            this.submissionId = submissionId;
            this.policyMemberId = policyMemberId;
        }

        @Override public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(submissionId, k.submissionId)
                && Objects.equals(policyMemberId, k.policyMemberId);
        }

        @Override public int hashCode() { return Objects.hash(submissionId, policyMemberId); }
    }
}
