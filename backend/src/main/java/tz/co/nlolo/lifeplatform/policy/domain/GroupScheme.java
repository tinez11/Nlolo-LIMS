package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;
import tz.co.nlolo.lifeplatform.policy.api.InterestMethod;
import tz.co.nlolo.lifeplatform.policy.api.RepaymentFrequency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The scheme configuration hanging off a master group policy.
 *
 * <p>Keyed by {@code policyNumber} and 1:1 with {@code policy.policy}: a row here IS the
 * statement "this policy is a scheme". Kept out of {@code policy.policy} so the 625
 * individual policies do not each carry six null group columns.
 */
@Entity
@Table(name = "group_scheme", schema = "policy")
public class GroupScheme {

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "benefit_basis", nullable = false)
    private BenefitBasis benefitBasis;

    @Column(name = "flat_benefit_amount")
    private BigDecimal flatBenefitAmount;

    @Column(name = "salary_multiple")
    private BigDecimal salaryMultiple;

    /** Null means the scheme has no limit -- NOT the same as a limit of zero. */
    @Column(name = "fcl_amount")
    private BigDecimal fclAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    /**
     * How this lender's loans repay principal. Null on every basis but AMORTISING_LOAN.
     *
     * <p>No setter, deliberately. A lender who changes how their book repays is a new
     * scheme, not an edit to this one: every member already on the roll was valued
     * against the old answer, and changing it here would silently restate all of them.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "interest_method")
    private InterestMethod interestMethod;

    /**
     * How often this lender's loans repay. Null on every basis but AMORTISING_LOAN.
     *
     * <p>On the scheme rather than on each member because a lender's product repays on
     * one cadence: asking for it on all 400 rows of a monthly file is 400 chances for
     * that file to disagree with itself. No setter, for the reason interestMethod has
     * none -- every member already on the roll had their schedule counted in periods of
     * the old answer.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "repayment_frequency")
    private RepaymentFrequency repaymentFrequency;

    /**
     * Percent per annum of a borrower's original principal. Null on every basis but
     * AMORTISING_LOAN; 0.5000 means 0.5%.
     *
     * <p>Here rather than on the product because the rate is what a lender negotiated: two
     * lenders writing business on the same filed credit-life product pay different rates, and
     * a product-level rate would force a duplicate product, and a duplicate TIRA filing, per
     * lender.
     *
     * <p>No setter, for the same reason interestMethod has none, and with more force: every
     * member already on the roll was CHARGED against the old rate and their refund will be
     * computed from what they paid. A rate change is a new scheme.
     */
    @Column(name = "premium_rate_percent")
    private BigDecimal premiumRatePercent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    protected GroupScheme() {}

    /**
     * A scheme on any basis but AMORTISING_LOAN, which needs an interest method.
     *
     * <p>Kept so the three employer bases did not have to start passing a null that means
     * nothing to them.
     */
    public GroupScheme(String policyNumber, UUID tenantId, BenefitBasis benefitBasis,
                        BigDecimal flatBenefitAmount, BigDecimal salaryMultiple,
                        BigDecimal fclAmount, String currency, String createdBy) {
        this(policyNumber, tenantId, benefitBasis, flatBenefitAmount, salaryMultiple,
            fclAmount, currency, null, null, null, createdBy);
    }

    public GroupScheme(String policyNumber, UUID tenantId, BenefitBasis benefitBasis,
                        BigDecimal flatBenefitAmount, BigDecimal salaryMultiple,
                        BigDecimal fclAmount, String currency,
                        InterestMethod interestMethod, RepaymentFrequency repaymentFrequency,
                        BigDecimal premiumRatePercent, String createdBy) {
        // Mirrors group_scheme_basis_parameter_present. The database is the guarantee;
        // this exists so a violation arrives as a domain error naming the problem rather
        // than as a constraint violation from three layers down.
        switch (benefitBasis) {
            case FLAT -> {
                requirePositive(flatBenefitAmount, "A flat-benefit scheme needs a benefit amount");
                requireAbsent(salaryMultiple, "A flat-benefit scheme cannot carry a salary multiple");
            }
            case SALARY_MULTIPLE -> {
                requirePositive(salaryMultiple, "A salary-multiple scheme needs a multiple");
                requireAbsent(flatBenefitAmount, "A salary-multiple scheme cannot carry a flat amount");
            }
            case GRADED -> {
                requireAbsent(flatBenefitAmount, "A graded scheme takes its amounts from its grade table");
                requireAbsent(salaryMultiple, "A graded scheme takes its amounts from its grade table");
            }
            case AMORTISING_LOAN -> {
                requireAbsent(flatBenefitAmount, "A credit-life scheme takes each amount from the member's own loan");
                requireAbsent(salaryMultiple, "A credit-life scheme takes each amount from the member's own loan");
                if (interestMethod == null) {
                    throw new IllegalArgumentException(
                        "A credit-life scheme must state how its lender's loans repay principal");
                }
                if (repaymentFrequency == null) {
                    throw new IllegalArgumentException(
                        "A credit-life scheme must state how often its lender's loans repay");
                }
                if (premiumRatePercent == null || premiumRatePercent.signum() <= 0) {
                    throw new IllegalArgumentException(
                        "A credit-life scheme must state the premium rate its lender agreed");
                }
            }
        }
        if (benefitBasis != BenefitBasis.AMORTISING_LOAN
                && (interestMethod != null || repaymentFrequency != null
                    || premiumRatePercent != null)) {
            throw new IllegalArgumentException(
                "An interest method, a repayment cadence and a premium rate belong only on a "
                    + "credit-life scheme");
        }
        if (fclAmount != null && fclAmount.signum() <= 0) {
            // Zero would send every member to underwriting, which is not what anybody
            // means by it. Absent is how "no limit" is said.
            throw new IllegalArgumentException(
                "A free cover limit of zero is not a limit -- leave it absent for a scheme with none");
        }

        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.benefitBasis = benefitBasis;
        this.flatBenefitAmount = flatBenefitAmount;
        this.salaryMultiple = salaryMultiple;
        this.fclAmount = fclAmount;
        this.currency = currency;
        this.interestMethod = interestMethod;
        this.repaymentFrequency = repaymentFrequency;
        this.premiumRatePercent = premiumRatePercent;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }

    private static void requirePositive(BigDecimal value, String message) {
        if (value == null || value.signum() <= 0) throw new IllegalArgumentException(message);
    }

    private static void requireAbsent(BigDecimal value, String message) {
        if (value != null) throw new IllegalArgumentException(message);
    }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getTenantId() { return tenantId; }
    public BenefitBasis getBenefitBasis() { return benefitBasis; }
    public BigDecimal getFlatBenefitAmount() { return flatBenefitAmount; }
    public BigDecimal getSalaryMultiple() { return salaryMultiple; }
    public BigDecimal getFclAmount() { return fclAmount; }
    public String getCurrency() { return currency; }
    public InterestMethod getInterestMethod() { return interestMethod; }
    public RepaymentFrequency getRepaymentFrequency() { return repaymentFrequency; }
    public BigDecimal getPremiumRatePercent() { return premiumRatePercent; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
