package tz.co.nlolo.lifeplatform.reinsurance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Maps {@code reinsurance.claim_recovery}. Write-once since IFRS 17 I3c: posted at claim approval, never confirmed
 * here ({@code confirmed_at} is set only on rows from before, when staff confirmed them). */
@Entity
@Table(name = "claim_recovery", schema = "reinsurance")
public class ClaimRecovery {

    @Id
    @GeneratedValue
    @Column(name = "recovery_id")
    private UUID recoveryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Opaque ref into `claims` -- never an FK. */
    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "treaty_id", nullable = false)
    private UUID treatyId;

    @Column(name = "recoverable_amount", nullable = false)
    private BigDecimal recoverableAmount;

    @Column(name = "recoverable_currency", nullable = false)
    private String recoverableCurrency;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected ClaimRecovery() {}

    public ClaimRecovery(UUID tenantId, UUID claimId, UUID treatyId, BigDecimal recoverableAmount,
                          String recoverableCurrency, String createdBy) {
        this.tenantId = tenantId;
        this.claimId = claimId;
        this.treatyId = treatyId;
        this.recoverableAmount = recoverableAmount;
        this.recoverableCurrency = recoverableCurrency;
        this.updatedBy = createdBy;
    }

    public UUID getRecoveryId() { return recoveryId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getClaimId() { return claimId; }
    public UUID getTreatyId() { return treatyId; }
    public BigDecimal getRecoverableAmount() { return recoverableAmount; }
    public String getRecoverableCurrency() { return recoverableCurrency; }
    public Instant getConfirmedAt() { return confirmedAt; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
