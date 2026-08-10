package tz.co.nlolo.lifeplatform.policyloan.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only ledger (Deliverable 3 Rev 2 §4) -- authoritative for outstanding balance, never
 * the schedule. Composite PK mirrors the partitioned table's own PRIMARY KEY(loan_transaction_id,
 * occurred_at) exactly -- the table's own comment: "partition key must be part of the PK." */
@Entity
@Table(name = "loan_transaction", schema = "policyloan")
@IdClass(LoanTransaction.LoanTransactionId.class)
public class LoanTransaction {

    @Id
    @Column(name = "loan_transaction_id")
    private UUID loanTransactionId;

    @Id
    @Column(name = "occurred_at")
    private Instant occurredAt;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "loan_id", nullable = false)
    private UUID loanId;

    @Column(name = "transaction_type", nullable = false)
    private String transactionType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TZS";

    @Column
    private String reference;

    protected LoanTransaction() {}

    public LoanTransaction(UUID tenantId, UUID loanId, String transactionType, BigDecimal amount, String currency, String reference) {
        this.loanTransactionId = UUID.randomUUID();
        this.occurredAt = Instant.now();
        this.tenantId = tenantId;
        this.loanId = loanId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.currency = currency;
        this.reference = reference;
    }

    public UUID getLoanTransactionId() { return loanTransactionId; }
    public Instant getOccurredAt() { return occurredAt; }
    public UUID getLoanId() { return loanId; }
    public String getTransactionType() { return transactionType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }

    public static class LoanTransactionId implements Serializable {
        private UUID loanTransactionId;
        private Instant occurredAt;

        public LoanTransactionId() {}
        public LoanTransactionId(UUID loanTransactionId, Instant occurredAt) {
            this.loanTransactionId = loanTransactionId;
            this.occurredAt = occurredAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof LoanTransactionId that)) return false;
            return Objects.equals(loanTransactionId, that.loanTransactionId) && Objects.equals(occurredAt, that.occurredAt);
        }

        @Override
        public int hashCode() { return Objects.hash(loanTransactionId, occurredAt); }
    }
}
