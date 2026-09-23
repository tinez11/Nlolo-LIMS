package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "premium_invoice", schema = "billing")
@IdClass(PremiumInvoice.PremiumInvoiceId.class)
public class PremiumInvoice {

    @Id
    @Column(name = "invoice_id")
    private UUID invoiceId = UUID.randomUUID();

    @Id
    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "billing_schedule_id", nullable = false)
    private UUID billingScheduleId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TZS";

    @Column(nullable = false)
    private String status = "DUE";

    @Column(name = "grace_period_ends_at")
    private LocalDate gracePeriodEndsAt;

    @Column(name = "waiver_reason")
    private String waiverReason;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BigDecimal.ZERO;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /**
     * The accepted enrolment file this charge is for. Null on every scheduled invoice.
     *
     * <p>Exactly one of this and {@link #billingScheduleId} is set —
     * {@code chk_premium_invoice_has_exactly_one_origin}. An invoice with neither belongs to
     * nothing and nobody can say why it exists; one with both claims two origins for one charge.
     */
    @Column(name = "enrolment_submission_id")
    private UUID enrolmentSubmissionId;

    protected PremiumInvoice() {}

    /** A scheduled invoice: one period of a recurring premium. */
    public PremiumInvoice(UUID tenantId, UUID billingScheduleId, String policyNumber,
                           LocalDate dueDate, BigDecimal amount, String currency, LocalDate gracePeriodEndsAt) {
        this.tenantId = tenantId;
        this.billingScheduleId = billingScheduleId;
        this.policyNumber = policyNumber;
        this.dueDate = dueDate;
        this.amount = amount;
        this.currency = currency;
        this.gracePeriodEndsAt = gracePeriodEndsAt;
    }

    /**
     * A single-premium invoice: one charge for one accepted enrolment file.
     *
     * <p>A separate factory rather than a null {@code billingScheduleId} on the constructor
     * above, so that neither kind can be created by accident: the two differ in what they
     * MEAN, not merely in which field happens to be populated.
     */
    public static PremiumInvoice forEnrolmentFile(UUID tenantId, UUID enrolmentSubmissionId,
                                                   String policyNumber, LocalDate dueDate,
                                                   BigDecimal amount, String currency,
                                                   LocalDate gracePeriodEndsAt) {
        PremiumInvoice invoice = new PremiumInvoice();
        invoice.tenantId = tenantId;
        invoice.enrolmentSubmissionId = enrolmentSubmissionId;
        invoice.policyNumber = policyNumber;
        invoice.dueDate = dueDate;
        invoice.amount = amount;
        invoice.currency = currency;
        invoice.gracePeriodEndsAt = gracePeriodEndsAt;
        return invoice;
    }

    // No markOverdue()/markInGrace() here -- unlike waive() (the REST-triggered staff action
    // below), DUE/IN_GRACE -> OVERDUE and DUE -> IN_GRACE are exclusively performed by
    // billing.sweep_billing_state()'s own SQL UPDATE statements, which write these columns
    // directly and bypass the JPA entity layer entirely (it runs SECURITY DEFINER, outside any
    // request's Hibernate session). A Java-side setter for either transition would be dead code.
    public void waive(String reason) { this.status = "WAIVED"; this.waiverReason = reason; }

    /**
     * M5: the money-in leg. PAID and PARTIALLY_PAID were declared in InvoiceStatus from M4 but
     * unreachable — nothing could transition into them until payment existed.
     *
     * <p>Partial payment is decided by comparing the cumulative paid amount against this
     * invoice's own amount, so an under-payment lands PARTIALLY_PAID rather than silently
     * counting as settled. WAIVED is terminal and is never overwritten by a late payment —
     * a payment arriving against a waived invoice is an operational anomaly, not a state change.
     */
    public void applyPayment(BigDecimal paidAmount) {
        if (paidAmount == null || paidAmount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive, got: " + paidAmount);
        }
        if ("WAIVED".equals(status) || "PAID".equals(status)) {
            return;
        }
        this.amountPaid = this.amountPaid == null ? paidAmount : this.amountPaid.add(paidAmount);
        this.status = this.amountPaid.compareTo(this.amount) >= 0 ? "PAID" : "PARTIALLY_PAID";
    }

    public UUID getInvoiceId() { return invoiceId; }
    public LocalDate getDueDate() { return dueDate; }
    public UUID getTenantId() { return tenantId; }
    public UUID getBillingScheduleId() { return billingScheduleId; }
    public UUID getEnrolmentSubmissionId() { return enrolmentSubmissionId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public LocalDate getGracePeriodEndsAt() { return gracePeriodEndsAt; }
    public BigDecimal getAmountPaid() { return amountPaid; }

    public static class PremiumInvoiceId implements Serializable {
        private UUID invoiceId;
        private LocalDate dueDate;

        public PremiumInvoiceId() {}
        public PremiumInvoiceId(UUID invoiceId, LocalDate dueDate) { this.invoiceId = invoiceId; this.dueDate = dueDate; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PremiumInvoiceId that)) return false;
            return Objects.equals(invoiceId, that.invoiceId) && Objects.equals(dueDate, that.dueDate);
        }

        @Override
        public int hashCode() { return Objects.hash(invoiceId, dueDate); }
    }
}
