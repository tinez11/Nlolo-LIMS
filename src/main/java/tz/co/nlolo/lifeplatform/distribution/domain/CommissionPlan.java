package tz.co.nlolo.lifeplatform.distribution.domain;

import tz.co.nlolo.lifeplatform.distribution.api.PlanStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * could not. {@code status} is a {@link PlanStatus} enum (Task 5): Task 3 left it as a plain
 * String, noting no dedicated type had been requested yet; Task 5 is the first task that actually
 * reads/writes it ({@code createCommissionPlan}/{@code getApplicablePlan}), so it is converted
 * here for consistency with {@code LicenseStatus}/{@code StatementStatus} -- see {@link PlanStatus}'s
 * own javadoc for the full reasoning.
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PlanStatus status = PlanStatus.ACTIVE;

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

    public void setStatus(PlanStatus status) { this.status = status; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public UUID getCommissionPlanId() { return commissionPlanId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public PlanStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
