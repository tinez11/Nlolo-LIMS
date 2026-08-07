package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "product_version", schema = "product")
public class ProductVersion {

    @Id
    @UuidGenerator
    @Column(name = "product_version_id")
    private UUID productVersionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "retirement_date")
    private LocalDate retirementDate;

    @Column(name = "is_active_for_new_business", nullable = false)
    private boolean activeForNewBusiness = true;

    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays;

    @Column(name = "max_loan_to_value_percent")
    private BigDecimal maxLoanToValuePercent;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "surrender_charge_schedule", columnDefinition = "jsonb")
    private String surrenderChargeScheduleJson;

    public String getSurrenderChargeScheduleJson() { return surrenderChargeScheduleJson; }
    public void setSurrenderChargeScheduleJson(String surrenderChargeScheduleJson) { this.surrenderChargeScheduleJson = surrenderChargeScheduleJson; }

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    protected ProductVersion() {}

    public ProductVersion(UUID tenantId, UUID productId, LocalDate effectiveDate, LocalDate retirementDate,
                           int gracePeriodDays, BigDecimal maxLoanToValuePercent, String createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.effectiveDate = effectiveDate;
        this.retirementDate = retirementDate;
        this.gracePeriodDays = gracePeriodDays;
        this.maxLoanToValuePercent = maxLoanToValuePercent;
        this.createdBy = createdBy;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public LocalDate getRetirementDate() { return retirementDate; }
    public boolean isActiveForNewBusiness() { return activeForNewBusiness; }
    public int getGracePeriodDays() { return gracePeriodDays; }
    public BigDecimal getMaxLoanToValuePercent() { return maxLoanToValuePercent; }

    /**
     * Version rollover: retires this version from new-business eligibility so a
     * subsequent version for the same product can become the sole active-for-new-business
     * row. Required by {@code ux_product_version_active}, the partial unique index on
     * {@code product_version(product_id) WHERE is_active_for_new_business = true} -- at
     * most one row per product may carry the flag, so publishing a second version must
     * retire the prior one first. Does not affect {@code retirementDate}/{@code effectiveDate}
     * (which govern {@code findActiveAsOf}'s point-in-time lookups) -- this flag governs
     * new-business eligibility specifically, a narrower concept.
     */
    public void retireFromNewBusiness() {
        this.activeForNewBusiness = false;
    }
}
