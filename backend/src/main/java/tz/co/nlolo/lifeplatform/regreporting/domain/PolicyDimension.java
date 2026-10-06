package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Maps {@code regreporting.policy_dimension} -- the attributes needed to ATTRIBUTE a policy
 * movement, arriving on {@code PolicyIssued} (the only event in the policy lifecycle that carries
 * {@code productId} and {@code sumAssured}; V2 section 5). {@code policyNumber} is an opaque ref
 * into {@code policy} -- never an FK (docs/06-database-schema.md:29).
 */
@Entity
@Table(name = "policy_dimension", schema = "regreporting")
@IdClass(PolicyDimensionId.class)
public class PolicyDimension {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "sum_assured_amount", nullable = false)
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency", nullable = false)
    private String sumAssuredCurrency;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected PolicyDimension() {}

    public PolicyDimension(UUID tenantId, String policyNumber, UUID productId,
                            BigDecimal sumAssuredAmount, String sumAssuredCurrency, LocalDate issueDate) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.productId = productId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.issueDate = issueDate;
    }

    /**
     * The scheme's total cover after a member joined or left.
     *
     * <p>The only mutation on this entity. Everything else about a policy dimension is fixed at
     * activation; the sum assured is not, because on a group scheme it is the total of a member
     * schedule that changes every month.
     *
     * <p><b>Refuses a non-positive total, and that is not defensive coding.</b> Zero is a valid
     * STARTING total -- a credit-life scheme is set up with no borrowers and activated at zero
     * (regreporting V7) -- but a scheme RESTATED to zero is one whose last member has left. That case belongs to the close
     * event: {@code PolicyApiImpl.exitOneMember} restates to zero and then closes the scheme, and
     * the resulting {@code policy.PolicySurrendered} terminates the last recorded total. A
     * restatement to zero here would remove cover the close is about to remove again — the whole
     * scheme counted out twice.
     */
    public void restateSumAssured(BigDecimal newTotal) {
        if (newTotal == null || newTotal.signum() <= 0) {
            throw new IllegalArgumentException("A policy dimension's sum assured must stay positive; "
                + "a scheme restated to " + newTotal + " is one the close event owns, not this one");
        }
        this.sumAssuredAmount = newTotal;
    }

    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getProductId() { return productId; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }
    public LocalDate getIssueDate() { return issueDate; }
    public Instant getCreatedAt() { return createdAt; }
}
