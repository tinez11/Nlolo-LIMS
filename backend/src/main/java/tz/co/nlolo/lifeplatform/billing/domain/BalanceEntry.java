package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One movement on a policy's balance (billing V14, money movement step 2): money in ({@code DEPOSIT}) or a premium paid
 * from it ({@code PREMIUM_PAID}). Add-only -- the policy's statement, and the auditor's trail, are these rows.
 */
@Entity
@Table(name = "balance_entry", schema = "billing")
public class BalanceEntry {

    public static final String DEPOSIT = "DEPOSIT";
    public static final String PREMIUM_PAID = "PREMIUM_PAID";

    /** A confirmed deposit or mobile-money request. */
    public static final String FROM_PAYMENT = "PAYMENT";
    /** Money above what a loan owed -- already posted by policyloan's rule E-03-SURPLUS, so it posts nothing here. */
    public static final String FROM_LOAN_SURPLUS = "LOAN_SURPLUS";

    @Id @Column(name = "entry_id") private UUID entryId = UUID.randomUUID();
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "entry_type", nullable = false) private String entryType;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "payment_id") private UUID paymentId;
    @Column(name = "invoice_id") private UUID invoiceId;
    @Column private String method;
    @Column private String reference;
    @Column(nullable = false) private String source = FROM_PAYMENT;
    @Column(name = "created_by") private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected BalanceEntry() {}

    public static BalanceEntry deposit(UUID tenantId, String policyNumber, BigDecimal amount, String currency,
                                       UUID paymentId, String method, String reference, String source, String by) {
        BalanceEntry e = new BalanceEntry();
        e.tenantId = tenantId;
        e.policyNumber = policyNumber;
        e.entryType = DEPOSIT;
        e.amount = amount;
        e.currency = currency;
        e.paymentId = paymentId;
        e.method = method;
        e.reference = reference;
        e.source = source;
        e.createdBy = by;
        return e;
    }

    public static BalanceEntry premiumPaid(UUID tenantId, String policyNumber, BigDecimal amount, String currency,
                                           UUID invoiceId) {
        BalanceEntry e = new BalanceEntry();
        e.tenantId = tenantId;
        e.policyNumber = policyNumber;
        e.entryType = PREMIUM_PAID;
        e.amount = amount;
        e.currency = currency;
        e.invoiceId = invoiceId;
        e.createdBy = "system";
        return e;
    }

    /** The entry's effect on the balance: a deposit adds, a premium paid takes away. */
    public BigDecimal signedAmount() {
        return DEPOSIT.equals(entryType) ? amount : amount.negate();
    }

    public UUID getEntryId() { return entryId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getEntryType() { return entryType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public UUID getPaymentId() { return paymentId; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getMethod() { return method; }
    public String getReference() { return reference; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
