package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.claim_dimension} -- the attribute needed to ATTRIBUTE a claims
 * movement, arriving on {@code ClaimRegistered} (the only event in the claim lifecycle that
 * carries {@code claimType}; {@code ClaimApproved}/{@code Rejected}/{@code Settled} do not,
 * V2 section 5). {@code claimId} and {@code policyNumber} are opaque refs -- never FKs
 * (docs/06-database-schema.md:29).
 */
@Entity
@Table(name = "claim_dimension", schema = "regreporting")
@IdClass(ClaimDimensionId.class)
public class ClaimDimension {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "claim_id")
    private UUID claimId;

    @Column(name = "claim_type", nullable = false)
    private String claimType;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ClaimDimension() {}

    public ClaimDimension(UUID tenantId, UUID claimId, String claimType, String policyNumber) {
        this.tenantId = tenantId;
        this.claimId = claimId;
        this.claimType = claimType;
        this.policyNumber = policyNumber;
    }

    public UUID getTenantId() { return tenantId; }
    public UUID getClaimId() { return claimId; }
    public String getClaimType() { return claimType; }
    public String getPolicyNumber() { return policyNumber; }
    public Instant getCreatedAt() { return createdAt; }
}
