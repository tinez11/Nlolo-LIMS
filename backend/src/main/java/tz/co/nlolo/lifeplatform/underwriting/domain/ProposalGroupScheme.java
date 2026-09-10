package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The terms of a group scheme as proposed, before any policy exists to carry them.
 *
 * <p>Its own table in the underwriting schema rather than a row in {@code policy.group_scheme},
 * for the plain reason that that table is keyed by {@code policy_number} and there is no policy
 * yet. Same reasoning, and the same shape, as {@link ProposalBeneficiary}.
 *
 * <p><b>Keyed by the case id.</b> A scheme proposal is 1:1 with its case, so a row here IS the
 * statement "this case is a scheme" — nothing else needs a discriminator column, and there is
 * no way to end up with a case that is half a scheme.
 *
 * <p>Immutable after creation. Terms are revised by re-taking the proposal, not by editing the
 * record of what the employer asked for; once a scheme is issued, {@code policy.group_scheme}
 * owns them.
 */
@Entity
@Table(name = "proposal_group_scheme", schema = "underwriting")
public class ProposalGroupScheme {

    @Id
    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "benefit_basis", nullable = false)
    private String benefitBasis;

    @Column(name = "flat_benefit_amount")
    private BigDecimal flatBenefitAmount;

    @Column(name = "salary_multiple")
    private BigDecimal salaryMultiple;

    @Column(name = "fcl_amount")
    private BigDecimal fclAmount;

    @Column(name = "currency", nullable = false)
    private String currency;

    /**
     * The premium agreed with the employer, not a computed one.
     *
     * <p>The individual premium formula prices ONE life from an age band and a sum assured
     * band. On a scheme the age it would read is the employer's — a company — and the sum
     * assured is every member's cover added together. Neither produces a number anybody would
     * charge, so a scheme's premium is negotiated and recorded, and issuance uses it verbatim.
     */
    @Column(name = "premium_amount", nullable = false)
    private BigDecimal premiumAmount;

    @Column(name = "premium_currency", nullable = false)
    private String premiumCurrency;

    @Column(name = "premium_frequency", nullable = false)
    private String premiumFrequency;

    @Column(name = "commencement_date")
    private LocalDate commencementDate;

    @Column(name = "policy_term_months")
    private Integer policyTermMonths;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    protected ProposalGroupScheme() {}

    public ProposalGroupScheme(UUID tenantId, UUID caseId, String benefitBasis,
                                BigDecimal flatBenefitAmount, BigDecimal salaryMultiple,
                                BigDecimal fclAmount, String currency, BigDecimal premiumAmount,
                                String premiumCurrency, String premiumFrequency,
                                LocalDate commencementDate, Integer policyTermMonths, String createdBy) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.benefitBasis = benefitBasis;
        this.flatBenefitAmount = flatBenefitAmount;
        this.salaryMultiple = salaryMultiple;
        this.fclAmount = fclAmount;
        this.currency = currency;
        this.premiumAmount = premiumAmount;
        this.premiumCurrency = premiumCurrency;
        this.premiumFrequency = premiumFrequency;
        this.commencementDate = commencementDate;
        this.policyTermMonths = policyTermMonths;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }

    public UUID getCaseId() { return caseId; }
    public UUID getTenantId() { return tenantId; }
    public String getBenefitBasis() { return benefitBasis; }
    public BigDecimal getFlatBenefitAmount() { return flatBenefitAmount; }
    public BigDecimal getSalaryMultiple() { return salaryMultiple; }
    public BigDecimal getFclAmount() { return fclAmount; }
    public String getCurrency() { return currency; }
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getPremiumCurrency() { return premiumCurrency; }
    public String getPremiumFrequency() { return premiumFrequency; }
    public LocalDate getCommencementDate() { return commencementDate; }
    public Integer getPolicyTermMonths() { return policyTermMonths; }
}
