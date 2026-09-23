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

    protected PremiumCredit() {}

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
