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
    /** Money taken back off the balance at a withdrawal's approval (billing V15, money movement step 3a). */
    public static final String WITHDRAWAL = "WITHDRAWAL";
    /** A withdrawal's transfer the rail refused: the money comes back as a deposit from this source, not fresh money. */
    public static final String FROM_WITHDRAWAL_RETURNED = "WITHDRAWAL_RETURNED";
    /** A deposit taken back because its money never arrived (billing V16, money movement step 3b). */
    public static final String REVERSAL = "REVERSAL";
    /** A premium paid from the balance, undone by a reversal: the money is back on the balance, the invoice owed again. */
    public static final String PREMIUM_REOPENED = "PREMIUM_REOPENED";

    /** A confirmed deposit or mobile-money request. */
    /** The entry types that add to the balance; every other type takes from it (as BalanceEntryRepository.balanceOf sums). */
    public static final java.util.Set<String> MONEY_IN = java.util.Set.of(DEPOSIT, PREMIUM_REOPENED);

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
    @Column(name = "withdrawal_id") private UUID withdrawalId;
    @Column(name = "reverses_entry_id") private UUID reversesEntryId;
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

    /** Money taken back off the balance at a withdrawal's approval. */
    public static BalanceEntry withdrawal(UUID tenantId, String policyNumber, BigDecimal amount, String currency,
                                          UUID withdrawalId, String by) {
        BalanceEntry e = new BalanceEntry();
        e.tenantId = tenantId;
        e.policyNumber = policyNumber;
        e.entryType = WITHDRAWAL;
        e.amount = amount;
        e.currency = currency;
        e.withdrawalId = withdrawalId;
        e.createdBy = by;
        return e;
    }

    /** A refused withdrawal's money back on the balance. */
    public static BalanceEntry returned(UUID tenantId, String policyNumber, BigDecimal amount, String currency,
                                        UUID withdrawalId) {
        BalanceEntry e = new BalanceEntry();
        e.tenantId = tenantId;
        e.policyNumber = policyNumber;
        e.entryType = DEPOSIT;
        e.amount = amount;
        e.currency = currency;
        e.reference = "withdrawal:" + withdrawalId;
        e.source = FROM_WITHDRAWAL_RETURNED;
        e.createdBy = "system";
        return e;
    }

    /** A deposit taken back: the whole of it, linked to the deposit it reverses. */
    public static BalanceEntry reversal(BalanceEntry deposit, String by) {
        BalanceEntry e = new BalanceEntry();
        e.tenantId = deposit.tenantId;
        e.policyNumber = deposit.policyNumber;
        e.entryType = REVERSAL;
        e.amount = deposit.amount;
        e.currency = deposit.currency;
        e.paymentId = null;
        e.method = deposit.method;
        e.reference = deposit.reference;
        e.reversesEntryId = deposit.entryId;
        e.createdBy = by;
        return e;
    }

    /** Part or all of a premium paid from the balance undone, linked to the PREMIUM_PAID it undoes. */
    public static BalanceEntry premiumReopened(BalanceEntry paid, BigDecimal amount, String by) {
        BalanceEntry e = new BalanceEntry();
        e.tenantId = paid.tenantId;
        e.policyNumber = paid.policyNumber;
        e.entryType = PREMIUM_REOPENED;
        e.amount = amount;
        e.currency = paid.currency;
        e.invoiceId = paid.invoiceId;
        e.reversesEntryId = paid.entryId;
        e.createdBy = by;
        return e;
    }

    public UUID getWithdrawalId() { return withdrawalId; }
    public UUID getReversesEntryId() { return reversesEntryId; }

    /** The entry's effect on the balance: a deposit or a reopened premium adds; everything else takes away. */
    public BigDecimal signedAmount() {
        return DEPOSIT.equals(entryType) || PREMIUM_REOPENED.equals(entryType) ? amount : amount.negate();
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
