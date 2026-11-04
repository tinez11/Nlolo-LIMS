package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Premium given back because a loan ended before its term.
 *
 * <p>A new row, never a mutation of the invoice it reverses — the same reasoning
 * {@code distribution.domain.CommissionAccrual} records for a clawback. The invoice says what
 * was charged and stays saying it; the credit says what came back off it. Netting them into
 * one figure destroys the only trail that can settle a dispute with a lender.
 *
 * <p>One per member, ever, enforced by {@code ux_premium_credit_per_member}: a member can be
 * exited more than once, and a second credit would refund the same premium again.
 */
@Entity
@Table(name = "premium_credit", schema = "billing")
public class PremiumCredit {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "credit_id")
    private UUID creditId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "policy_member_id", nullable = false)
    private UUID policyMemberId;

    /** The invoice that charged this borrower, found through its enrolment submission. */
    @Column(name = "original_invoice_id", nullable = false)
    private UUID originalInvoiceId;

    @Column(name = "amount", nullable = false)
    private BigDecimal amount;

    @Column(name = "currency", nullable = false)
    private String currency = "TZS";

    @Column(name = "exit_reason", nullable = false)
    private String exitReason;

    @Column(name = "exit_date", nullable = false)
    private LocalDate exitDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * The part of this credit paid back to the lender in money (billing V13, 2026-10-30): what it had already paid of
     * the file. Null when the credit only lowered what was still owed.
     */
    @Column(name = "refund_amount")
    private BigDecimal refundAmount;

    @Column(name = "refund_payee")
    private String refundPayee;

    @Column(name = "refund_status")
    private String refundStatus;

    @Column(name = "refund_disbursement_id")
    private UUID refundDisbursementId;

    @Column(name = "refund_paid_at")
    private Instant refundPaidAt;

    @Column(name = "refund_decided_by")
    private String refundDecidedBy;

    @Column(name = "refund_decided_at")
    private Instant refundDecidedAt;

    @Column(name = "refund_refusal_reason")
    private String refundRefusalReason;

    protected PremiumCredit() {}

    /** Part of this credit goes back to the lender by bank transfer: the part of the file it had already paid. */
    public void requestRefund(BigDecimal refund, String payee) {
        if (refund.signum() <= 0 || refund.compareTo(amount) > 0) {
            throw new IllegalArgumentException("A refund of " + refund + " against a credit of " + amount);
        }
        this.refundAmount = refund;
        this.refundPayee = payee;
        // Money movement step 4 (billing V17): raised automatically, released to the bank-transfer queue by a second person.
        this.refundStatus = "AWAITING_APPROVAL";
    }

    /** payouts.approve releases the refund: AWAITING_APPROVAL -> REQUESTED, and payment is asked for the transfer. */
    public void approveRefund(String by) {
        decideRefund(by);
        this.refundStatus = "REQUESTED";
    }

    /** payouts.approve refuses it, saying why: it stays owed to the lender on the books, as a failed one does. */
    public void refuseRefund(String by, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say why the refund is refused");
        }
        decideRefund(by);
        this.refundStatus = "REFUSED";
        this.refundRefusalReason = reason.trim();
    }

    private void decideRefund(String by) {
        if (!"AWAITING_APPROVAL".equals(refundStatus)) {
            throw new IllegalStateException("This lender refund is not waiting for approval (it is " + refundStatus + ")");
        }
        this.refundDecidedBy = by;
        this.refundDecidedAt = Instant.now();
    }

    /** @return whether this call made it PAID -- false on a redelivery, which must post nothing twice */
    public boolean refundPaid(UUID disbursementId) {
        if ("PAID".equals(refundStatus)) {
            return false;
        }
        this.refundStatus = "PAID";
        this.refundDisbursementId = disbursementId;
        this.refundPaidAt = Instant.now();
        return true;
    }

    public void refundFailed() {
        if (!"PAID".equals(refundStatus)) {
            this.refundStatus = "FAILED";
        }
    }

    public BigDecimal getRefundAmount() { return refundAmount; }
    public String getRefundPayee() { return refundPayee; }
    public String getRefundStatus() { return refundStatus; }
    public String getRefundDecidedBy() { return refundDecidedBy; }
    public String getRefundRefusalReason() { return refundRefusalReason; }

    public PremiumCredit(UUID tenantId, String policyNumber, UUID policyMemberId,
                          UUID originalInvoiceId, BigDecimal amount, String currency,
                          String exitReason, LocalDate exitDate) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException(
                "A credit of " + amount + " is not a refund; do not raise one");
        }
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.policyMemberId = policyMemberId;
        this.originalInvoiceId = originalInvoiceId;
        this.amount = amount;
        this.currency = currency;
        this.exitReason = exitReason;
        this.exitDate = exitDate;
    }

    public UUID getCreditId() { return creditId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getPolicyMemberId() { return policyMemberId; }
    public UUID getOriginalInvoiceId() { return originalInvoiceId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getExitReason() { return exitReason; }
    public LocalDate getExitDate() { return exitDate; }
    public Instant getCreatedAt() { return createdAt; }
}
