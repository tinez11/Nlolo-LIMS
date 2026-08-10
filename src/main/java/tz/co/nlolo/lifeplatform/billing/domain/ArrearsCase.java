package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/**
 * dunningLevel has no setter deliberately — Task 5's billing.sweep_billing_state() SQL function
 * is the sole writer of business-state dunning_level/status columns; the Java entity only ever
 * reads dunningLevel to decide what to notify and writes lastNotifiedDunningLevel/resolvedAt,
 * which are its own concerns. This asymmetry is intentional.
 */
@Entity
@Table(name = "arrears_case", schema = "billing")
public class ArrearsCase {

    @Id
    @Column(name = "arrears_case_id")
    private UUID arrearsCaseId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "dunning_level", nullable = false)
    private int dunningLevel = 1;

    @Column(name = "last_notified_dunning_level", nullable = false)
    private int lastNotifiedDunningLevel = 0;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    protected ArrearsCase() {}

    public ArrearsCase(UUID tenantId, UUID invoiceId, String policyNumber) {
        this.tenantId = tenantId;
        this.invoiceId = invoiceId;
        this.policyNumber = policyNumber;
    }

    public void resolve() { this.resolvedAt = Instant.now(); }
    public void markNotified(int level) { this.lastNotifiedDunningLevel = level; }

    public UUID getArrearsCaseId() { return arrearsCaseId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getInvoiceId() { return invoiceId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getDunningLevel() { return dunningLevel; }
    public int getLastNotifiedDunningLevel() { return lastNotifiedDunningLevel; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
