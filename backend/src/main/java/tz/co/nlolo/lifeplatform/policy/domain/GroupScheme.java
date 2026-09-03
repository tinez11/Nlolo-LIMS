package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.policy.api.BenefitBasis;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    protected GroupScheme() {}

    public GroupScheme(String policyNumber, UUID tenantId, BenefitBasis benefitBasis,
                        BigDecimal flatBenefitAmount, BigDecimal salaryMultiple,
                        BigDecimal fclAmount, String currency, String createdBy) {
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
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
