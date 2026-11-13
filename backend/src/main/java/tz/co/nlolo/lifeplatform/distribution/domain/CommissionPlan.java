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

    /** The agent whose own rate this is: their plan pointer moves to it only once it is approved. */
    @Column(name = "proposed_for_agent_id")
    private UUID proposedForAgentId;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    @Column(name = "acted_alone", nullable = false)
    private boolean actedAlone;

    protected CommissionPlan() {}

    public CommissionPlan(UUID tenantId, UUID productId, String createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.createdBy = createdBy;
    }

    /**
     * A plan proposed, waiting for a second person (2026-11-12, finance finding 190). {@code forAgent} names the agent
     * whose own rate it is, or null for the product's plan.
     */
    public static CommissionPlan proposed(UUID tenantId, UUID productId, UUID forAgent, String createdBy) {
        CommissionPlan plan = new CommissionPlan(tenantId, productId, createdBy);
        plan.status = PlanStatus.PROPOSED;
        plan.proposedForAgentId = forAgent;
        return plan;
    }

    /** Approved: it earns from now. {@code alone} when its proposer approved it, holding commission.approve-own. */
    public void approve(String by, boolean alone) {
        requireProposed("approved");
        status = PlanStatus.ACTIVE;
        decidedBy = by;
        decidedAt = Instant.now();
        actedAlone = alone;
    }

    public void reject(String by, String reason, boolean alone) {
        requireProposed("rejected");
        status = PlanStatus.REJECTED;
        decidedBy = by;
        decidedAt = Instant.now();
        decisionReason = reason;
        actedAlone = alone;
    }

    private void requireProposed(String what) {
        if (status != PlanStatus.PROPOSED) {
            throw new IllegalStateException("Only a proposed plan can be " + what + "; this one is " + status);
        }
    }

    public UUID getProposedForAgentId() { return proposedForAgentId; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionReason() { return decisionReason; }
    public boolean isActedAlone() { return actedAlone; }

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
