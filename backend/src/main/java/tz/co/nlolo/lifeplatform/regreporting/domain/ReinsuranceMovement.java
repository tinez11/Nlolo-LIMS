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
 * Maps {@code regreporting.reinsurance_movement} -- gross ceded risk and premium for one
 * {@code (tenant, period)} (V2 section 6). Deliberately NOT attributed by product: a cession
 * arrives from reinsurance's own transaction reacting to {@code PolicyIssued}, unordered against
 * this module's own {@code PolicyIssued} listener, so keying by product would race against the
 * dimension row a cession needs. Both measures default to zero and are incremented together by
 * {@link #applyCeded(BigDecimal, BigDecimal)}, matching the DB's
 * {@code reinsurance_movement_non_negative} CHECK.
 */
@Entity
@Table(name = "reinsurance_movement", schema = "regreporting")
@IdClass(ReinsuranceMovementId.class)
public class ReinsuranceMovement {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "period")
    private String period;

    @Column(name = "ceded_risk_amount", nullable = false)
    private BigDecimal cededRiskAmount = BigDecimal.ZERO;

    @Column(name = "ceded_premium_amount", nullable = false)
    private BigDecimal cededPremiumAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic lock (regreporting/V3, M10 final review C1). THE HIGHEST-CONTENTION of the four
     * fact tables, precisely because of the no-product-grain decision above: this is ONE row per
     * {@code (tenant, period)}, so every cession for a tenant-quarter read-modify-writes it. Without
     * a version, two concurrent cessions could both read the same ceded totals, both add their own,
     * and lose one silently. {@code ReinsuranceEventListener} retries a bounded 3 attempts on the
     * resulting {@code ObjectOptimisticLockingFailureException}, re-fetching fresh each time.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected ReinsuranceMovement() {}

    /** Creates the row for {@code (tenantId, period)} with both measures at zero. */
    public ReinsuranceMovement(UUID tenantId, String period, String currency) {
        this.tenantId = tenantId;
        this.period = period;
        this.currency = currency;
    }

    public void applyCeded(BigDecimal cededRisk, BigDecimal cededPremium) {
        this.cededRiskAmount = this.cededRiskAmount.add(cededRisk);
        this.cededPremiumAmount = this.cededPremiumAmount.add(cededPremium);
        this.updatedAt = Instant.now();
    }

    public UUID getTenantId() { return tenantId; }
    public String getPeriod() { return period; }
    public BigDecimal getCededRiskAmount() { return cededRiskAmount; }
    public BigDecimal getCededPremiumAmount() { return cededPremiumAmount; }
    public String getCurrency() { return currency; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
