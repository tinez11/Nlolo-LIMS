package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "field_receipt", schema = "billing")
public class FieldReceipt {

    @Id
    @Column(name = "receipt_id")
    private UUID receiptId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TZS";

    @Column(name = "client_idempotency_key", nullable = false)
    private String clientIdempotencyKey;

    @Column(name = "captured_at_client", nullable = false)
    private Instant capturedAtClient;

    @Column(name = "captured_at_server", nullable = false)
    private Instant capturedAtServer = Instant.now();

    @Column(nullable = false)
    private String status = "PENDING_RECONCILIATION";

    @Column(name = "reconciled_at")
    private Instant reconciledAt;

    @Column(name = "notified_overdue_at")
    private Instant notifiedOverdueAt;

    protected FieldReceipt() {}

    public FieldReceipt(UUID tenantId, String policyNumber, UUID agentId, BigDecimal amount, String currency,
                         String clientIdempotencyKey, Instant capturedAtClient) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.agentId = agentId;
        this.amount = amount;
        this.currency = currency;
        this.clientIdempotencyKey = clientIdempotencyKey;
        this.capturedAtClient = capturedAtClient;
    }

    public void markNotifiedOverdue() { this.notifiedOverdueAt = Instant.now(); }

    /** M5: closes the offline-receipt SLA loop (docs/02-module-architecture.md:78). Until now
     * PENDING_RECONCILIATION -> RECONCILED had no implementation, so
     * billing.sweep_billing_state()'s step 5 could only ever escalate receipts to
     * RECONCILIATION_OVERDUE and never clear them. */
    public void reconcile() {
        if ("RECONCILED".equals(status)) {
            return;
        }
        this.status = "RECONCILED";
        this.reconciledAt = Instant.now();
    }

    public UUID getReceiptId() { return receiptId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getAgentId() { return agentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getClientIdempotencyKey() { return clientIdempotencyKey; }
    public Instant getCapturedAtClient() { return capturedAtClient; }
    public Instant getCapturedAtServer() { return capturedAtServer; }
    public String getStatus() { return status; }
    public Instant getReconciledAt() { return reconciledAt; }
    public Instant getNotifiedOverdueAt() { return notifiedOverdueAt; }
}
