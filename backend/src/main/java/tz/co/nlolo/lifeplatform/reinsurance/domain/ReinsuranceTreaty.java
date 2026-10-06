package tz.co.nlolo.lifeplatform.reinsurance.domain;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStatus;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code reinsurance.reinsurance_treaty} (V1 + V2).
 *
 * <p><b>Treaty capacity/layer limits are NOT modelled (I3, final review).</b> A real SURPLUS
 * treaty has a finite line capacity and a real XOL treaty is "N excess of M" -- a layer with an
 * upper limit -- but this entity carries no capacity/limit column of any kind, and nothing
 * enforces one: see {@link RecoveryCalculator#excessOfLoss} for the concrete consequence (XOL
 * cover is effectively unlimited above retention as implemented). A missing concept, not a bug in
 * what exists; deferred in the design spec's §8 pending an actual business rule for what happens
 * above capacity, which no document on this platform currently defines.
 */
@Entity
@Table(name = "reinsurance_treaty", schema = "reinsurance")
public class ReinsuranceTreaty {

    @Id
    @GeneratedValue
    @Column(name = "treaty_id")
    private UUID treatyId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "reinsurer_name", nullable = false)
    private String reinsurerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "treaty_type", nullable = false)
    private TreatyType treatyType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TreatyStatus status = TreatyStatus.ACTIVE;

    @Column(name = "retention_limit_amount", nullable = false)
    private BigDecimal retentionLimitAmount;

    @Column(name = "retention_limit_currency", nullable = false)
    private String retentionLimitCurrency;

    /** Non-null exactly for QUOTA_SHARE -- V2's treaty_cession_percent_required_for_quota_share. */
    @Column(name = "cession_percent")
    private BigDecimal cessionPercent;

    /** IFRS 17 I3c (guide K-02): the reinsurer's commission not contingent on claims, a percent of the ceded premium
     * on each bordereau -- a reduction of the reinsurance premium (IFRS 17 para 86). Stated on every treaty; 0 is
     * a real answer. */
    @Column(name = "commission_percent", nullable = false)
    private BigDecimal commissionPercent = BigDecimal.ZERO;

    /** IFRS 17 I3c: an XOL treaty's flat yearly premium, charged one twelfth on each monthly bordereau. XOL only;
     * null when the treaty states none. */
    @Column(name = "xol_annual_premium")
    private BigDecimal xolAnnualPremium;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    @Column(name = "effective_to")
    private LocalDate effectiveTo;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected ReinsuranceTreaty() {}

    public ReinsuranceTreaty(UUID tenantId, String reinsurerName, TreatyType treatyType,
                              BigDecimal retentionLimitAmount, String retentionLimitCurrency,
                              BigDecimal cessionPercent, LocalDate effectiveFrom, LocalDate effectiveTo,
                              String createdBy) {
        this.tenantId = tenantId;
        this.reinsurerName = reinsurerName;
        this.treatyType = treatyType;
        this.retentionLimitAmount = retentionLimitAmount;
        this.retentionLimitCurrency = retentionLimitCurrency;
        this.cessionPercent = cessionPercent;
        this.effectiveFrom = effectiveFrom;
        this.effectiveTo = effectiveTo;
        this.createdBy = createdBy;
    }

    public ReinsuranceTreaty(UUID tenantId, String reinsurerName, TreatyType treatyType,
                              BigDecimal retentionLimitAmount, String retentionLimitCurrency,
                              BigDecimal cessionPercent, BigDecimal commissionPercent, BigDecimal xolAnnualPremium,
                              LocalDate effectiveFrom, LocalDate effectiveTo, String createdBy) {
        this(tenantId, reinsurerName, treatyType, retentionLimitAmount, retentionLimitCurrency, cessionPercent,
            effectiveFrom, effectiveTo, createdBy);
        this.commissionPercent = commissionPercent;
        this.xolAnnualPremium = xolAnnualPremium;
    }

    /** ACTIVE and its effective window covers {@code date}. An open-ended treaty (null
     * effectiveTo) never expires by date. Used by Task 4's selection. */
    public boolean isActiveOn(LocalDate date) {
        return status == TreatyStatus.ACTIVE
            && !effectiveFrom.isAfter(date)
            && (effectiveTo == null || !effectiveTo.isBefore(date));
    }

    public void expire(String expiredBy) {
        this.status = TreatyStatus.EXPIRED;
        this.updatedAt = Instant.now();
        this.updatedBy = expiredBy;
    }

    public UUID getTreatyId() { return treatyId; }
    public UUID getTenantId() { return tenantId; }
    public String getReinsurerName() { return reinsurerName; }
    public TreatyType getTreatyType() { return treatyType; }
    public TreatyStatus getStatus() { return status; }
    public BigDecimal getRetentionLimitAmount() { return retentionLimitAmount; }
    public String getRetentionLimitCurrency() { return retentionLimitCurrency; }
    public BigDecimal getCessionPercent() { return cessionPercent; }
    public BigDecimal getCommissionPercent() { return commissionPercent; }
    public BigDecimal getXolAnnualPremium() { return xolAnnualPremium; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
