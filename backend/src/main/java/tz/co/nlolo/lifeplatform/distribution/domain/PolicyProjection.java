package tz.co.nlolo.lifeplatform.distribution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code distribution.policy_projection} 1:1 (db-migrations/distribution/V2 section 8) --
 * distribution's OWN state, not a cache of policy's. {@code policy} is not in distribution's
 * {@code allowedDependencies}, so this projection (built solely from {@code policy.PolicyIssued})
 * is how distribution answers "whose commission do I claw back?" and "which agent and plan?"
 * without ever calling {@code PolicyApi}.
 *
 * <p>Composite primary key {@code (tenant_id, policy_number)}, unlike every other entity in this
 * module whose PK is a single generated UUID -- see {@link PolicyProjectionId}.
 */
@Entity
@Table(name = "policy_projection", schema = "distribution")
@IdClass(PolicyProjectionId.class)
public class PolicyProjection {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    /** Resolved from {@code agentOfRecordId}; null when a policy was sold direct. */
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "premium_amount", nullable = false)
    private BigDecimal premiumAmount;

    @Column(name = "premium_currency", nullable = false)
    private String premiumCurrency;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    /** The policy's first collected invoice, or null when none has been collected yet. Holds the
     * identity rather than a boolean so that a redelivered {@code billing.PremiumCollected} for
     * that same first invoice is still recognisable as the first -- see the column's own comment
     * in V2 section 8, and {@code PremiumEventListener}'s guard. */
    @Column(name = "first_invoice_id")
    private UUID firstInvoiceId;

    @Column(name = "lapsed_at")
    private Instant lapsedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PolicyProjection() {}

    public PolicyProjection(UUID tenantId, String policyNumber, UUID agentId, UUID productId,
                             BigDecimal premiumAmount, String premiumCurrency, LocalDate issueDate) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.agentId = agentId;
        this.productId = productId;
        this.premiumAmount = premiumAmount;
        this.premiumCurrency = premiumCurrency;
        this.issueDate = issueDate;
    }

    public void markFirstInvoiceCollected(UUID invoiceId) { this.firstInvoiceId = invoiceId; }
    public void markLapsed(Instant lapsedAt) { this.lapsedAt = lapsedAt; }

    /** True when {@code invoiceId} is this policy's first collection, INCLUDING a redelivery of
     * it -- the distinction that makes the RENEWAL guard idempotent. */
    public boolean isFirstCollection(UUID invoiceId) {
        return firstInvoiceId == null || firstInvoiceId.equals(invoiceId);
    }

    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getAgentId() { return agentId; }
    public UUID getProductId() { return productId; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getPremiumCurrency() { return premiumCurrency; }
    public LocalDate getIssueDate() { return issueDate; }
    public UUID getFirstInvoiceId() { return firstInvoiceId; }
    public Instant getLapsedAt() { return lapsedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
