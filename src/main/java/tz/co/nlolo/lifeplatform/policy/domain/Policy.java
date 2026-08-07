package tz.co.nlolo.lifeplatform.policy.domain;

import tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * State machine per docs/03-aggregate-design.md §3 (verbatim graph in this plan's header
 * Architecture note). Guard methods throw InvalidPolicyStateException (policy.api) directly
 * rather than a bare IllegalStateException -- see Global Constraints for why an unrestricted,
 * HIGHEST_PRECEDENCE module advice cannot safely catch a generic JDK exception type here
 * (TenantContext.get()'s fail-loud guard also throws IllegalStateException, for an unrelated
 * reason, and would be misclassified as a 409 app-wide if this class threw the same type).
 *
 * SURRENDERED and MATURED are valid enum values (the DB CHECK and every view type must be
 * able to represent a policy that reaches them via a later milestone) but no method on this
 * class transitions into either -- the surrender/maturity choreography is deferred wholesale
 * (see plan header). There is deliberately no surrender()/matureTo() method here.
 */
@Entity
@Table(name = "policy", schema = "policy")
public class Policy {

    @Id
    @Column(name = "policy_number")
    private String policyNumber;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policyholder_party_id", nullable = false)
    private UUID policyholderPartyId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "product_category")
    private String productCategory;

    @Column(name = "agent_of_record_id")
    private UUID agentOfRecordId;

    @Column(nullable = false)
    private String status = "PROPOSED";

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "sum_assured_amount", nullable = false)
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency", nullable = false)
    private String sumAssuredCurrency = "TZS";

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "lapsed_at")
    private Instant lapsedAt;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected Policy() {}

    public Policy(String policyNumber, UUID tenantId, UUID policyholderPartyId, UUID productId, UUID productVersionId,
                  String productCategory, UUID agentOfRecordId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, String createdBy) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.policyholderPartyId = policyholderPartyId;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.productCategory = productCategory;
        this.agentOfRecordId = agentOfRecordId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.createdBy = createdBy;
    }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getTenantId() { return tenantId; }
    public UUID getPolicyholderPartyId() { return policyholderPartyId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public String getProductCategory() { return productCategory; }
    public UUID getAgentOfRecordId() { return agentOfRecordId; }
    public String getStatus() { return status; }
    public LocalDate getIssueDate() { return issueDate; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public String getSuspensionReason() { return suspensionReason; }
    public Instant getLapsedAt() { return lapsedAt; }

    public void activate(LocalDate issueDate) {
        if (!"PROPOSED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " cannot be issued from status " + status);
        }
        this.status = "ACTIVE";
        this.issueDate = issueDate;
    }

    public void suspend(String reason) {
        if (!"ACTIVE".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be ACTIVE to be SUSPENDED (current: " + status + ")");
        }
        this.status = "SUSPENDED";
        this.suspendedAt = Instant.now();
        this.suspensionReason = reason;
    }

    public void resume() {
        if (!"SUSPENDED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be SUSPENDED to resume (current: " + status + ")");
        }
        this.status = "ACTIVE";
        this.suspendedAt = null;
        this.suspensionReason = null;
    }

    public void lapse() {
        if (!"ACTIVE".equals(status) && !"SUSPENDED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be ACTIVE or SUSPENDED to LAPSE (current: " + status + ")");
        }
        this.status = "LAPSED";
        this.lapsedAt = Instant.now();
    }

    /** LAPSED -> REINSTATED directly (not a second write to ACTIVE) -- a reinstated policy stays
     * labeled REINSTATED going forward, distinguishing it for audit/actuarial purposes from a
     * policy that was continuously ACTIVE. isInForce() treats both as equivalent for coverage
     * purposes. Window-eligibility (TZ_REINSTATEMENT_WINDOW_MONTHS) is checked by the caller
     * (PolicyApiImpl, Task 2) before this is invoked, using lapsedAt exposed above. */
    public void reinstate() {
        if (!"LAPSED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be LAPSED to be REINSTATED (current: " + status + ")");
        }
        this.status = "REINSTATED";
    }

    public boolean isInForce() {
        return "ACTIVE".equals(status) || "REINSTATED".equals(status);
    }
}
