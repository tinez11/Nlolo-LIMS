package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Never mutated on a premium change -- old schedule TERMINATED, new one created ACTIVE
 * (db-migrations/billing/V1's own column comment). One ACTIVE schedule per in-force policy at
 * a time; billing.application.BillingApiImpl enforces that invariant, not a DB constraint
 * (mirrors policy.domain.Policy's own state-machine-in-code-not-DB convention).
 */
@Entity
@Table(name = "billing_schedule", schema = "billing")
public class BillingSchedule {

    @Id
    @Column(name = "billing_schedule_id")
    private UUID billingScheduleId = UUID.randomUUID();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "premium_frequency", nullable = false)
    private String premiumFrequency;

    @Column(name = "premium_amount", nullable = false)
    private BigDecimal premiumAmount;

    @Column(name = "premium_currency", nullable = false)
    private String premiumCurrency = "TZS";

    @Column(name = "next_due_date")
    private LocalDate nextDueDate;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected BillingSchedule() {}

    public BillingSchedule(UUID tenantId, String policyNumber, String premiumFrequency,
                            BigDecimal premiumAmount, String premiumCurrency, LocalDate nextDueDate) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.premiumFrequency = premiumFrequency;
        this.premiumAmount = premiumAmount;
        this.premiumCurrency = premiumCurrency;
        this.nextDueDate = nextDueDate;
    }

    public void suspend() { this.status = "SUSPENDED"; }
    public void terminate() { this.status = "TERMINATED"; }
    public void reactivate() { this.status = "ACTIVE"; }
    public void advanceNextDueDate(LocalDate next) { this.nextDueDate = next; }

    public UUID getBillingScheduleId() { return billingScheduleId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getPremiumFrequency() { return premiumFrequency; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getPremiumCurrency() { return premiumCurrency; }
    public LocalDate getNextDueDate() { return nextDueDate; }
    public String getStatus() { return status; }
}
