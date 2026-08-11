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

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PremiumInvoice() {}

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

    // No markOverdue()/markInGrace() here -- unlike waive() (the REST-triggered staff action
    // below), DUE/IN_GRACE -> OVERDUE and DUE -> IN_GRACE are exclusively performed by
    // billing.sweep_billing_state()'s own SQL UPDATE statements, which write these columns
    // directly and bypass the JPA entity layer entirely (it runs SECURITY DEFINER, outside any
    // request's Hibernate session). A Java-side setter for either transition would be dead code.
    public void waive(String reason) { this.status = "WAIVED"; this.waiverReason = reason; }

    public UUID getInvoiceId() { return invoiceId; }
    public LocalDate getDueDate() { return dueDate; }
    public UUID getTenantId() { return tenantId; }
    public UUID getBillingScheduleId() { return billingScheduleId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public LocalDate getGracePeriodEndsAt() { return gracePeriodEndsAt; }

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
