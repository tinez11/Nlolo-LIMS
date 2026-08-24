package tz.co.nlolo.lifeplatform.reinsurance.domain;

import tz.co.nlolo.lifeplatform.reinsurance.api.InvalidRecoveryStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Maps {@code reinsurance.claim_recovery}. Mutable exactly once, via {@link #confirm}. */
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

    /**
     * Throws rather than returning quietly on a repeat, deliberately. The caller must be able to
     * distinguish a real transition from a double-confirm, because {@code
     * reinsurance.RecoveryConfirmed} may be published ONLY on the former -- M6's I1 finding, where
     * an unconditional publish after an idempotent transition emitted a duplicate event to
     * finaccounting, and a duplicate there is a double journal entry. A 409 is also the honest
     * answer to a human clicking confirm twice.
     */
    public void confirm(Instant when, String confirmedBy) {
        if (confirmedAt != null) {
            throw new InvalidRecoveryStateException(
                "Recovery " + recoveryId + " was already confirmed at " + confirmedAt);
        }
        this.confirmedAt = when;
        this.updatedAt = Instant.now();
        this.updatedBy = confirmedBy;
    }

    public boolean isConfirmed() { return confirmedAt != null; }

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
