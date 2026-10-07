package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One payment applied to an invoice (billing V10): when it arrived, the rail's reference and who paid. The
 * invoice keeps only a running amount paid; this is what a customer's payment schedule shows against it.
 */
@Entity
@Table(name = "premium_receipt", schema = "billing")
public class PremiumReceipt {
    @Id @Column(name = "receipt_id") private UUID receiptId = UUID.randomUUID();
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "invoice_id", nullable = false) private UUID invoiceId;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "payment_reference") private String paymentReference;
    @Column(name = "payer_ref") private String payerRef;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected PremiumReceipt() {}

    public PremiumReceipt(UUID tenantId, String policyNumber, UUID invoiceId, BigDecimal amount, String currency,
                          Instant receivedAt, String paymentReference, String payerRef) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.invoiceId = invoiceId;
        this.amount = amount;
        this.currency = currency;
        this.receivedAt = receivedAt;
        this.paymentReference = paymentReference;
        this.payerRef = payerRef;
    }

    public UUID getReceiptId() { return receiptId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getInvoiceId() { return invoiceId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public Instant getReceivedAt() { return receivedAt; }
    public String getPaymentReference() { return paymentReference; }
    public String getPayerRef() { return payerRef; }
}
