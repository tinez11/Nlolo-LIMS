package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;

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

    /**
     * GMM or PAA, decided per version (V10).
     *
     * <p>It lived on {@code product_definition}, where {@code publishVersion}'s unconditional
     * activation rewrote it on every republish — silently changing the measurement basis of every
     * contract already issued under the product. Here it cannot: a policy pins a
     * {@code productVersionId}, so a new version carries a new model and the old one keeps its own.
     *
     * <p>It also belongs next to its own evidence. PAA eligibility turns on the coverage period,
     * and {@code minTermMonths}/{@code maxTermMonths} are on this row.
     */
    @Column(name = "ifrs_measurement_model", nullable = false)
    private String ifrsMeasurementModel;

    // What this version will accept (V6). All nullable -- an unbounded dimension is a
    // real product design, not a gap. Consumed by Build 4's issueGates, where entry age
    // and term are hard refusals and sum assured is a soft flag; the severities live in
    // the gate rather than here because they are properties of the kind of bound.
    @Column(name = "min_entry_age")
    private Integer minEntryAge;

    @Column(name = "max_entry_age")
    private Integer maxEntryAge;

    @Column(name = "min_term_months")
    private Integer minTermMonths;

    @Column(name = "max_term_months")
    private Integer maxTermMonths;

    @Column(name = "min_sum_assured")
    private BigDecimal minSumAssured;

    @Column(name = "max_sum_assured")
    private BigDecimal maxSumAssured;

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
                           int gracePeriodDays, BigDecimal maxLoanToValuePercent,
                           String ifrsMeasurementModel, String createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.effectiveDate = effectiveDate;
        this.retirementDate = retirementDate;
        this.gracePeriodDays = gracePeriodDays;
        this.maxLoanToValuePercent = maxLoanToValuePercent;
        this.ifrsMeasurementModel = ifrsMeasurementModel;
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
    public String getIfrsMeasurementModel() { return ifrsMeasurementModel; }

    /**
     * Record what this version will accept.
     *
     * <p>Ordering (each max at or above its min) is validated by
     * {@link EligibilityBounds} itself, so a caller cannot construct an inverted pair to
     * pass here; {@code product_version_*_sane} enforces the same in the database.
     */
    public void applyEligibilityBounds(EligibilityBounds bounds) {
        this.minEntryAge = bounds.minEntryAge();
        this.maxEntryAge = bounds.maxEntryAge();
        this.minTermMonths = bounds.minTermMonths();
        this.maxTermMonths = bounds.maxTermMonths();
        this.minSumAssured = bounds.minSumAssured();
        this.maxSumAssured = bounds.maxSumAssured();
    }

    public EligibilityBounds getEligibilityBounds() {
        return new EligibilityBounds(minEntryAge, maxEntryAge, minTermMonths, maxTermMonths,
            minSumAssured, maxSumAssured);
    }

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
