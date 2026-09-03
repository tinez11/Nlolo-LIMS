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

    // Who is insured, as opposed to who owns the contract (V7). On most life business
    // these are two different people, and a death claim is assessed against this one.
    // Always populated going forward: the service resolves a self-insured policy to the
    // policyholder rather than storing a null every reader has to interpret.
    @Column(name = "life_assured_party_id")
    private UUID lifeAssuredPartyId;

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

    // The policy term (V6). Null together on a product that does not term -- whole
    // life, an annuity, an annually renewable group scheme -- and on every policy
    // issued before the migration. maturityDate is derived in applyTerm and stored so
    // a maturity sweep can index it; policy_maturity_matches_term keeps it honest.
    @Column(name = "commencement_date")
    private LocalDate commencementDate;

    @Column(name = "policy_term_months")
    private Integer policyTermMonths;

    @Column(name = "premium_paying_term_months")
    private Integer premiumPayingTermMonths;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

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

    /**
     * Record the contract's timeline, deriving the maturity date from it.
     *
     * <p>**The one place maturity is computed on this platform.** It is stored rather
     * than derived on read because a maturity sweep needs an indexable column, and a
     * value every caller re-derives is a value one of them eventually derives wrong --
     * the same failure shape as the unordered-query sweep in PLAN.md §10, where
     * "whichever row came back first" decided money.
     *
     * <p>The guards below mirror policy_term_positive,
     * policy_premium_paying_term_within_term and policy_maturity_matches_term. The
     * database is the guarantee; these exist so a violation arrives as a domain error
     * naming the problem rather than as a constraint violation from three layers down.
     *
     * <p>All three arguments may be null together: whole life, an annuity and an
     * annually renewable group scheme genuinely have no term, and that is not the same
     * as a term nobody recorded.
     */
    public void applyTerm(LocalDate commencementDate, Integer policyTermMonths,
                          Integer premiumPayingTermMonths) {
        if (policyTermMonths != null && policyTermMonths <= 0) {
            throw new IllegalArgumentException("Policy term must be a positive number of months");
        }
        if (premiumPayingTermMonths != null && premiumPayingTermMonths <= 0) {
            throw new IllegalArgumentException("Premium-paying term must be a positive number of months");
        }
        if (premiumPayingTermMonths != null && policyTermMonths != null
                && premiumPayingTermMonths > policyTermMonths) {
            // A limited-payment policy pays for LESS time than it covers. Longer is not
            // a product, it is a data error.
            throw new IllegalArgumentException(
                "Premium-paying term cannot exceed the policy term");
        }

        this.commencementDate = commencementDate;
        this.policyTermMonths = policyTermMonths;
        this.premiumPayingTermMonths = premiumPayingTermMonths;
        this.maturityDate = (commencementDate != null && policyTermMonths != null)
            ? commencementDate.plusMonths(policyTermMonths)
            : null;
    }

    /**
     * Restate a group scheme's sum assured after its member schedule changed.
     *
     * <p>A scheme's sum assured <b>is</b> the total of what its members are covered for.
     * Leaving it at the inception figure would mean a scheme that has taken on 40 joiners
     * still reports the total it was issued with -- a number that is wrong on the policy
     * list, wrong on a reinsurance return, and wrong in exactly the direction that
     * understates exposure.
     *
     * <p>Refused on anything but a GROUP_LIFE policy, deliberately. Without that guard
     * this is a public "set the sum assured" method on the aggregate, and an individual
     * policy's sum assured may only move by endorsement -- a rule this would quietly
     * route around.
     */
    public void restateSumAssured(BigDecimal total) {
        if (!"GROUP_LIFE".equals(productCategory)) {
            throw new InvalidPolicyStateException(
                "Only a group scheme's sum assured is restated from its members; policy "
                    + policyNumber + " is " + productCategory + " and changes by endorsement");
        }
        if (total == null || total.signum() <= 0) {
            // Mirrors policy_sum_assured_positive. Reaching zero means the last member
            // left, and an empty scheme is a scheme to close, not one to carry at nil.
            throw new IllegalArgumentException(
                "A scheme's sum assured must stay positive; a scheme with no covered members should be lapsed");
        }
        this.sumAssuredAmount = total;
        this.updatedAt = Instant.now();
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
    /** Record who is insured. Arrives already resolved; see PolicyApi.IssueRequest. */
    public void recordLifeAssured(UUID lifeAssuredPartyId) {
        this.lifeAssuredPartyId = lifeAssuredPartyId;
    }

    public UUID getLifeAssuredPartyId() { return lifeAssuredPartyId; }
    public LocalDate getCommencementDate() { return commencementDate; }
    public Integer getPolicyTermMonths() { return policyTermMonths; }
    public Integer getPremiumPayingTermMonths() { return premiumPayingTermMonths; }
    public LocalDate getMaturityDate() { return maturityDate; }
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
