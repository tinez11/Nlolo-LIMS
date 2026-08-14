package tz.co.nlolo.lifeplatform.distribution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code distribution.commission_plan} 1:1 (db-migrations/distribution/V1:30-38, V2 section 4).
 * Promoted to its own aggregate root (Deliverable 3 Rev 2, Di1) -- supports first-year/renewal/
 * override/supervisor-override/threshold tiers, which a flat percentage on {@link AgentProfile}
 * could not. {@code status} is a plain string ({@code "ACTIVE"}/{@code "RETIRED"}, V1:34) rather
 * than a dedicated enum: no {@code PlanStatus} type was requested for this task, and the DB CHECK
 * remains the backstop.
 */
@Entity
@Table(name = "commission_plan", schema = "distribution")
public class CommissionPlan {

    @Id
    @UuidGenerator
    @Column(name = "commission_plan_id")
    private UUID commissionPlanId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(nullable = false)
    private String status = "ACTIVE";

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected CommissionPlan() {}

    public CommissionPlan(UUID tenantId, UUID productId, String createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.createdBy = createdBy;
    }

    public void setStatus(String status) { this.status = status; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public UUID getCommissionPlanId() { return commissionPlanId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public String getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
