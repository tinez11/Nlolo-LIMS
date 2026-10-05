package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.UUID;

/**
 * A customer changing their mind inside the free-look window (guide §21.3).
 *
 * <p>The refund is the premiums collected less what the insurer actually spent getting the policy
 * on the books -- a medical examination, a courier. Those deductions are ITEMISED rather than a
 * percentage, because a customer exercising a statutory right to walk away is owed an explanation
 * of every shilling withheld, and a flat percentage is not one.
 *
 * <p>Two people, as everywhere else money leaves: the person who prepares the figures is not the
 * person who releases them.
 */
@Entity
@Table(name = "free_look_cancellation", schema = "benefitpayout")
public class FreeLookCancellation {

    @Id
    @UuidGenerator
    @Column(name = "cancellation_id")
    private UUID cancellationId;

    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String status = "REQUESTED";
    @Column(name = "premiums_collected", nullable = false) private BigDecimal premiumsCollected;
    @Column(name = "refund_amount", nullable = false) private BigDecimal refundAmount;
    @Column(nullable = false) private String currency;
    @Column(name = "payee_ref", nullable = false) private String payeeRef;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "disbursement_id") private UUID disbursementId;
    @Version private long version;

    protected FreeLookCancellation() {}

    public static FreeLookCancellation request(UUID tenantId, String policyNumber, BigDecimal premiumsCollected,
                                               BigDecimal deductionsTotal, String currency, String payeeRef,
                                               String requestedBy) {
        BigDecimal premiums = premiumsCollected.setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal deductions = deductionsTotal.setScale(2, RoundingMode.HALF_EVEN);
        // A refund can be nothing; it can never be a BILL. Deductions above the premiums would
        // turn a customer exercising a statutory right into a debtor.
        if (deductions.compareTo(premiums) > 0) {
            throw new PayoutStateException("Deductions of " + deductions + " exceed the " + premiums
                + " premiums collected");
        }
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new PayoutStateException("A free-look refund needs a payee reference");
        }
        FreeLookCancellation c = new FreeLookCancellation();
        c.tenantId = tenantId;
        c.policyNumber = policyNumber;
        c.premiumsCollected = premiums;
        c.refundAmount = premiums.subtract(deductions);
        c.currency = currency;
        c.payeeRef = payeeRef;
        c.requestedBy = requestedBy;
        return c;
    }

    /**
     * A unit-linked policy's free-look (product step 6): its refund is the unwinding of its own entries and units,
     * known only once the units are sold at the first price after the cancellation -- so it is recorded at nothing
     * and released by {@link #releaseUnitLinkedRefund} (plan R6, deviation D2: no new column).
     */
    public static FreeLookCancellation requestUnitLinked(UUID tenantId, String policyNumber, BigDecimal premiumsCollected,
                                                         String currency, String payeeRef, String requestedBy) {
        FreeLookCancellation c = request(tenantId, policyNumber, premiumsCollected, premiumsCollected, currency, payeeRef,
            requestedBy);
        c.refundAmount = BigDecimal.ZERO.setScale(2);
        return c;
    }

    /** The unwound refund, once unitlinked has sold the units. Only on an approved, still unpaid cancellation. */
    public void releaseUnitLinkedRefund(BigDecimal refund) {
        if (!"APPROVED".equals(status)) {
            throw new PayoutStateException("Free-look cancellation " + cancellationId + " is " + status
                + "; only an approved one has its unit-linked refund released");
        }
        if (refund == null || refund.signum() < 0) {
            throw new PayoutStateException("A free-look refund can be nothing, never negative");
        }
        this.refundAmount = refund.setScale(2, RoundingMode.HALF_EVEN);
    }

    public void approve(String approver) {
        if (!"REQUESTED".equals(status)) {
            throw new PayoutStateException("Free-look cancellation " + cancellationId + " is " + status
                + ", not awaiting approval");
        }
        if (approver == null || approver.equals(requestedBy)) {
            throw new PayoutStateException("A free-look cancellation must be approved by someone other than the "
                + "person who requested it (" + requestedBy + ")");
        }
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
        this.status = "APPROVED";
    }

    /** Idempotent, so a redelivered disbursement completion cannot reopen a closed cancellation. */
    public void markPaid(UUID disbursementId) {
        if ("APPROVED".equals(status)) {
            this.status = "PAID";
            this.disbursementId = disbursementId;
        }
    }

    public void markFailed() {
        if ("APPROVED".equals(status)) {
            this.status = "FAILED";
        }
    }

    public UUID getCancellationId() { return cancellationId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getStatus() { return status; }
    public BigDecimal getPremiumsCollected() { return premiumsCollected; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public String getCurrency() { return currency; }
    public String getPayeeRef() { return payeeRef; }
    public String getRequestedBy() { return requestedBy; }
    public String getApprovedBy() { return approvedBy; }
}
