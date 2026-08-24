package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.claims_movement} -- GROSS per-cause claims movements for one
 * {@code (tenant, period, claimType)} (V2 section 6). Every measure defaults to zero and is
 * incremented by exactly one {@code apply*} method per movement cause, matching the DB's
 * {@code claims_movement_non_negative} CHECK.
 */
@Entity
@Table(name = "claims_movement", schema = "regreporting")
@IdClass(ClaimsMovementId.class)
public class ClaimsMovement {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "period")
    private String period;

    @Id
    @Column(name = "claim_type")
    private String claimType;

    @Column(name = "registered_count", nullable = false)
    private int registeredCount = 0;

    @Column(name = "approved_count", nullable = false)
    private int approvedCount = 0;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount = 0;

    @Column(name = "settled_count", nullable = false)
    private int settledCount = 0;

    @Column(name = "approved_amount", nullable = false)
    private BigDecimal approvedAmount = BigDecimal.ZERO;

    @Column(name = "settled_amount", nullable = false)
    private BigDecimal settledAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic lock (regreporting/V3, M10 final review C1) -- see {@link PolicyMovement#getVersion()}'s
     * field javadoc for the read-modify-write race this closes. {@code ClaimsEventListener}
     * maintains this row the same way and retries the same bounded 3 attempts.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ClaimsMovement() {}

    /** Creates the row for {@code (tenantId, period, claimType)} with every measure at zero. */
    public ClaimsMovement(UUID tenantId, String period, String claimType, String currency) {
        this.tenantId = tenantId;
        this.period = period;
        this.claimType = claimType;
        this.currency = currency;
    }

    public void applyRegistered() {
        this.registeredCount++;
        this.updatedAt = Instant.now();
    }

    public void applyApproved(BigDecimal amount) {
        this.approvedCount++;
        this.approvedAmount = this.approvedAmount.add(amount);
        this.updatedAt = Instant.now();
    }

    public void applyRejected() {
        this.rejectedCount++;
        this.updatedAt = Instant.now();
    }

    public void applySettled(BigDecimal amount) {
        this.settledCount++;
        this.settledAmount = this.settledAmount.add(amount);
        this.updatedAt = Instant.now();
    }

    public UUID getTenantId() { return tenantId; }
    public String getPeriod() { return period; }
    public String getClaimType() { return claimType; }
    public int getRegisteredCount() { return registeredCount; }
    public int getApprovedCount() { return approvedCount; }
    public int getRejectedCount() { return rejectedCount; }
    public int getSettledCount() { return settledCount; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public String getCurrency() { return currency; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
