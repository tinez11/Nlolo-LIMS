package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.EligibilityBounds;
import tz.co.nlolo.lifeplatform.product.api.FrequencyLoading;
import tz.co.nlolo.lifeplatform.product.api.TiraFiling;

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

    /**
     * Months from cover start during which a suicide, or a pre-existing condition, may be
     * cited as a decline reason. Null where the product has no such exclusion -- every product
     * before credit life, whose behaviour is unchanged.
     */
    @Column(name = "suicide_exclusion_months")
    private Integer suicideExclusionMonths;

    @Column(name = "pre_existing_exclusion_months")
    private Integer preExistingExclusionMonths;

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
    @Column(name = "ifrs_measurement_model")
    private String ifrsMeasurementModel;

    /**
     * IFRS 17 I2 (product V27): what the actuary signs off at publish. The measurement model itself is the accounting
     * policy register's, resolved when a policy is classified; a version only carries an override, which the register
     * must allow for the portfolio. The legacy {@code ifrs_measurement_model} above is retired (nullable, no longer
     * asked for) -- read as an override it would contradict the register for every version that stored GMM by habit.
     */
    @Column(name = "expected_profitability_bucket", nullable = false)
    private String expectedProfitabilityBucket = "REMAINING";

    @Column(name = "measurement_model_override")
    private String measurementModelOverride;

    /** IFRS 17 I3b (product V28): the investment component share (%) of a survival or income instalment. */
    @Column(name = "survival_ic_percent")
    private BigDecimal survivalInvestmentComponentPercent;

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

    // What this version charges for paying in instalments (V11). Defaulted to ZERO in the field
    // initialiser as well as the column default, so a version built in memory is unloaded rather
    // than null -- the entity is constructed before Hibernate ever sees the DEFAULT.
    @Column(name = "monthly_loading_percent", nullable = false)
    private BigDecimal monthlyLoadingPercent = BigDecimal.ZERO;

    @Column(name = "quarterly_loading_percent", nullable = false)
    private BigDecimal quarterlyLoadingPercent = BigDecimal.ZERO;

    // The TIRA filing that authorises this version (V12). Nullable ONLY for the versions that
    // predate the rule; publishVersion refuses a null for anything new.
    @Column(name = "tira_filing_reference")
    private String tiraFilingReference;

    @Column(name = "tira_approval_date")
    private LocalDate tiraApprovalDate;

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
    /** When the version was published, and by whom (the product page's version list). */
    public java.time.Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    /** Null clears a window; a product with no such exclusion is the normal case. */
    public void setExclusionPeriods(Integer suicideMonths, Integer preExistingMonths) {
        requirePositiveOrAbsent("suicide", suicideMonths);
        requirePositiveOrAbsent("pre-existing", preExistingMonths);
        this.suicideExclusionMonths = suicideMonths;
        this.preExistingExclusionMonths = preExistingMonths;
    }

    private static void requirePositiveOrAbsent(String which, Integer months) {
        if (months != null && months <= 0) {
            throw new IllegalArgumentException("A " + which + " exclusion of " + months
                + " months is not a window; leave it absent for a product with no such exclusion");
        }
    }

    public Integer getSuicideExclusionMonths() { return suicideExclusionMonths; }
    public Integer getPreExistingExclusionMonths() { return preExistingExclusionMonths; }

    public int getGracePeriodDays() { return gracePeriodDays; }
    public BigDecimal getMaxLoanToValuePercent() { return maxLoanToValuePercent; }
    public String getIfrsMeasurementModel() { return ifrsMeasurementModel; }
    public String getExpectedProfitabilityBucket() { return expectedProfitabilityBucket; }
    public String getMeasurementModelOverride() { return measurementModelOverride; }
    public BigDecimal getSurvivalInvestmentComponentPercent() { return survivalInvestmentComponentPercent; }

    /** Set once, at publish, before the version is saved. */
    public void applyIfrs17Terms(String expectedProfitabilityBucket, String measurementModelOverride,
                                 BigDecimal survivalInvestmentComponentPercent) {
        this.expectedProfitabilityBucket = expectedProfitabilityBucket;
        this.measurementModelOverride = measurementModelOverride;
        this.survivalInvestmentComponentPercent = survivalInvestmentComponentPercent;
    }

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
     * Record what this version charges for instalment payment.
     *
     * <p>Bounds (0-100) are validated by {@link FrequencyLoading} itself, so a caller cannot
     * construct an out-of-range pair to pass here; {@code product_version_frequency_loading_sane}
     * enforces the same at the database.
     */
    public void applyFrequencyLoading(FrequencyLoading loading) {
        this.monthlyLoadingPercent = loading.monthlyPercent();
        this.quarterlyLoadingPercent = loading.quarterlyPercent();
    }

    public FrequencyLoading getFrequencyLoading() {
        return new FrequencyLoading(monthlyLoadingPercent, quarterlyLoadingPercent);
    }

    public void applyTiraFiling(TiraFiling filing) {
        this.tiraFilingReference = filing.reference();
        this.tiraApprovalDate = filing.approvalDate();
    }

    /**
     * The filing, or NULL for a version published before V12.
     *
     * <p>Null rather than an empty {@link TiraFiling}: there is no such thing as a filing that is
     * present and empty, and the record's own constructor would refuse to build one.
     */
    public TiraFiling getTiraFiling() {
        return tiraFilingReference == null || tiraApprovalDate == null
            ? null
            : new TiraFiling(tiraFilingReference, tiraApprovalDate);
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
