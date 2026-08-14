package tz.co.nlolo.lifeplatform.distribution.domain;

import tz.co.nlolo.lifeplatform.distribution.api.LicenseStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code distribution.agent_profile} 1:1 (db-migrations/distribution/V1:6-25). {@code partyId}
 * is an opaque reference into {@code party} -- onboarding requires KYC VERIFIED, checked at the
 * application layer (Task 4+), never here. {@code hierarchyParentId} is the self-referencing FK
 * backing the agent hierarchy; like every other FK on this platform it is stored as a plain scalar
 * column rather than a JPA association (no entity in this codebase uses {@code @ManyToOne}).
 */
@Entity
@Table(name = "agent_profile", schema = "distribution")
public class AgentProfile {

    @Id
    @UuidGenerator
    @Column(name = "agent_id")
    private UUID agentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "license_number", nullable = false)
    private String licenseNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "license_status", nullable = false)
    private LicenseStatus licenseStatus = LicenseStatus.ACTIVE;

    @Column(name = "license_expiry_date", nullable = false)
    private LocalDate licenseExpiryDate;

    @Column(name = "hierarchy_parent_id")
    private UUID hierarchyParentId;

    @Column(name = "commission_plan_id")
    private UUID commissionPlanId;

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

    protected AgentProfile() {}

    public AgentProfile(UUID tenantId, UUID partyId, String licenseNumber, LocalDate licenseExpiryDate,
                         UUID hierarchyParentId, UUID commissionPlanId, String createdBy) {
        this.tenantId = tenantId;
        this.partyId = partyId;
        this.licenseNumber = licenseNumber;
        this.licenseExpiryDate = licenseExpiryDate;
        this.hierarchyParentId = hierarchyParentId;
        this.commissionPlanId = commissionPlanId;
        this.createdBy = createdBy;
    }

    /** An agent must hold an ACTIVE license to accrue commission. An EXPIRED or SUSPENDED agent
     * is not an error -- the accrual is simply skipped and logged, because the sale itself was
     * still valid and the policy still exists. */
    public boolean canAccrueCommission() {
        return licenseStatus == LicenseStatus.ACTIVE;
    }

    public void setLicenseStatus(LicenseStatus licenseStatus) { this.licenseStatus = licenseStatus; }
    public void setLicenseExpiryDate(LocalDate licenseExpiryDate) { this.licenseExpiryDate = licenseExpiryDate; }
    public void setHierarchyParentId(UUID hierarchyParentId) { this.hierarchyParentId = hierarchyParentId; }
    public void setCommissionPlanId(UUID commissionPlanId) { this.commissionPlanId = commissionPlanId; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public UUID getAgentId() { return agentId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPartyId() { return partyId; }
    public String getLicenseNumber() { return licenseNumber; }
    public LicenseStatus getLicenseStatus() { return licenseStatus; }
    public LocalDate getLicenseExpiryDate() { return licenseExpiryDate; }
    public UUID getHierarchyParentId() { return hierarchyParentId; }
    public UUID getCommissionPlanId() { return commissionPlanId; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
