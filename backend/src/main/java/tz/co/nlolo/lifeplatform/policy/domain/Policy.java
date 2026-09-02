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
 * SURRENDERED and MATURED are the two terminal statuses. M6 added the only two transitions into
 * them -- {@link #mature()} and {@link #terminateForSettledClaim()}, both driven by a SETTLED claim
 * (a settled claim discharges the coverage, so the policy must stop being invoiced). The wider
 * surrender/maturity choreography (customer-initiated surrender, term expiry) is still deferred;
 * there is deliberately no general-purpose surrender() entry point here.
 *
 * <p><b>Both terminal statuses are one-way.</b> {@link #reinstate()} requires LAPSED, so nothing on
 * this class can move a policy back out of MATURED/SURRENDERED -- see
 * {@code claims.domain.Claim.reopen()}'s javadoc for the documented consequence (M6 final-review
 * I6): reopening a SETTLED claim cannot reverse the policy closure its settlement caused.
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

    @Column(name = "premium_amount", nullable = false)
    private BigDecimal premiumAmount;

    @Column(name = "premium_currency", nullable = false)
    private String premiumCurrency = "TZS";

    @Column(name = "premium_frequency", nullable = false)
    private String premiumFrequency;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "lapsed_at")
    private Instant lapsedAt;

    @Column(name = "underwriting_case_id")
    private UUID underwritingCaseId;

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
                  String productCategory, UUID agentOfRecordId, BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                  BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency, UUID underwritingCaseId,
                  String createdBy) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.policyholderPartyId = policyholderPartyId;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.productCategory = productCategory;
        this.agentOfRecordId = agentOfRecordId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.premiumAmount = premiumAmount;
        this.premiumCurrency = premiumCurrency;
        this.premiumFrequency = premiumFrequency;
        this.underwritingCaseId = underwritingCaseId;
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
    public BigDecimal getPremiumAmount() { return premiumAmount; }
    public String getPremiumCurrency() { return premiumCurrency; }
    public String getPremiumFrequency() { return premiumFrequency; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public String getSuspensionReason() { return suspensionReason; }
    public Instant getLapsedAt() { return lapsedAt; }
    public UUID getUnderwritingCaseId() { return underwritingCaseId; }

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

    /**
     * Whether {@link #lapse()} would succeed, so a caller can ask instead of attempting and
     * catching. Extracted from {@code lapse()}'s own guard rather than restated, so there is one
     * definition of "lapsable" and the question and the action cannot drift apart.
     *
     * <p>This exists because catching {@link InvalidPolicyStateException} across a
     * {@code @Transactional} boundary does not work: the inner boundary marks the whole
     * transaction rollback-only before the caller ever sees the exception, so the commit fails
     * with {@code UnexpectedRollbackException} no matter how carefully the caller handles it.
     * {@code policyloan}'s forced lapse hit exactly that, and asking first is the fix.
     */
    public boolean canLapse() {
        return "ACTIVE".equals(status) || "SUSPENDED".equals(status);
    }

    public void lapse() {
        if (!canLapse()) {
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

    /**
     * A MATURITY claim settled, or the policy reached term. Terminal.
     *
     * <p><b>Accepted source states: ACTIVE, REINSTATED, LAPSED, SUSPENDED</b> (see
     * {@link #closeableBySettledClaim()}). Already-closed (MATURED <i>or</i> SURRENDERED) is a
     * satisfied post-condition and returns silently (see {@link #alreadyClosed()}); every other
     * status -- PROPOSED above all -- still throws, because "a claim settled against a policy that
     * was never issued" is nonsense, not a state to absorb.
     */
    public void mature() {
        if (alreadyClosed()) {
            return;
        }
        if (!closeableBySettledClaim()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be ACTIVE, REINSTATED, LAPSED or SUSPENDED to MATURE (current: " + status + ")");
        }
        this.status = "MATURED";
    }

    /**
     * A DEATH/DISABILITY/CRITICAL_ILLNESS claim settled: coverage is discharged. Terminal.
     * Uses SURRENDERED because policy.policy's CHECK offers no CLAIM_SETTLED value and
     * "coverage discharged, no further premium due" is the operative meaning both share.
     *
     * <p><b>Accepted source states: ACTIVE, REINSTATED, LAPSED, SUSPENDED</b> (see
     * {@link #closeableBySettledClaim()}). Already-closed (SURRENDERED <i>or</i> MATURED) is a
     * satisfied post-condition and returns silently (see {@link #alreadyClosed()}); every other
     * status -- PROPOSED above all -- still throws.
     */
    public void terminateForSettledClaim() {
        if (alreadyClosed()) {
            return;
        }
        if (!closeableBySettledClaim()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " must be ACTIVE, REINSTATED, LAPSED or SUSPENDED to terminate for a settled claim (current: " + status + ")");
        }
        this.status = "SURRENDERED";
    }

    /**
     * M6 final-review fix (C1, part 2). Either terminal status satisfies the post-condition both
     * closure methods exist to achieve -- "this policy is closed, so billing must stop generating
     * premium invoices against it" -- so reaching the OTHER one is a silent no-op, not an error.
     *
     * <p>Before this fix each method only no-opped on its own target status and threw on its
     * sibling: a second claim settling against an already-closed policy (e.g. a DEATH claim
     * settling after a MATURITY claim already matured the policy) threw
     * {@code InvalidPolicyStateException} from inside claims' settlement listener, where -- until
     * the same fix isolated that call -- it rolled back the claim's own SETTLED transition after
     * the money had already left. Nothing is gained by failing here: the goal is already met.
     */
    private boolean alreadyClosed() {
        return "MATURED".equals(status) || "SURRENDERED".equals(status);
    }

    /**
     * M6 final-review fix (C1, part 2). LAPSED and SUSPENDED are accepted alongside
     * ACTIVE/REINSTATED, because a settled claim discharges the coverage whether or not premiums
     * were still being paid:
     * <ul>
     *   <li><b>LAPSED</b> is reached automatically and unattended --
     *       {@code policy.application.PolicyLapseRecommendedEventListener} calls
     *       {@code lapsePolicy} on {@code billing.PolicyLapseRecommended} at dunning level >= 5.
     *       A deceased policyholder stops paying premiums, so ANY death claim whose assessment
     *       outlasts dunning escalation arrives here. Refusing to close such a policy left billing
     *       invoicing a policy whose claim had already been paid out -- the exact bug closing the
     *       policy exists to prevent.</li>
     *   <li><b>SUSPENDED</b> for the same reason: coverage on hold is not coverage discharged, and
     *       "stop invoicing this policy" is equally correct once a claim against it has paid.</li>
     * </ul>
     */
    private boolean closeableBySettledClaim() {
        return "ACTIVE".equals(status) || "REINSTATED".equals(status)
            || "LAPSED".equals(status) || "SUSPENDED".equals(status);
    }
}
