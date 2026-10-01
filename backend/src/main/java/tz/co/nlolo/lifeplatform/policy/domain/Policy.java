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

    // When a customer surrender took effect (V30). Cover stops on this date; a death before it is
    // covered, on or after it is not. Null on a policy surrendered by a settled claim.
    @Column(name = "surrender_effective_date")
    private LocalDate surrenderEffectiveDate;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "lapsed_at")
    private Instant lapsedAt;

    @Column(name = "underwriting_case_id")
    private UUID underwritingCaseId;

    /** V24: why an exception-path issuance happened, in its own words, and who did it. */
    @Column(name = "issuance_basis", updatable = false)
    private String issuanceBasis;

    @Column(name = "issuance_reason", updatable = false)
    private String issuanceReason;

    @Column(name = "issued_by_name", updatable = false)
    private String issuedByName;

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
        // A contract paid ONCE cannot have a paying term of several months, and the two had
        // been free to disagree: policies exist carrying MONTHLY with a paying term of 1, which
        // reads as "monthly instalments, paid for one month" and is neither. Nothing anywhere
        // refused it, because each field is independently valid.
        //
        // Only the SINGLE direction is enforced here. The recurring direction -- that MONTHLY
        // over a twelve-month term ought to pay for twelve of them -- is a real gap too, but
        // rejecting it now would invalidate policies already written, so it stays a known one.
        if ("SINGLE".equals(premiumFrequency)
                && premiumPayingTermMonths != null && premiumPayingTermMonths != 1) {
            throw new IllegalArgumentException(
                "A SINGLE premium is charged once, so its premium-paying term must be 1 month "
                    + "(or absent), not " + premiumPayingTermMonths);
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
     * <p>Refused on anything but a scheme, deliberately. Without that guard this is a
     * public "set the sum assured" method on the aggregate, and an individual policy's
     * sum assured may only move by endorsement -- a rule this would quietly route around.
     *
     * <p>CREDIT_LIFE is a scheme too. Its total is the sum of the loans it insures, and
     * it moves every time a borrower is enrolled; without this the first monthly file
     * would leave a lender's contract stating the total it was issued with.
     */
    public void restateSumAssured(BigDecimal total) {
        if (!"GROUP_LIFE".equals(productCategory) && !"CREDIT_LIFE".equals(productCategory)) {
            throw new InvalidPolicyStateException(
                "Only a scheme's sum assured is restated from its members; policy "
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

    /**
     * Who earns commission on this contract from now on. Changes nothing already earned -- an
     * accrual is booked against the agent of record at the moment it is earned -- so a correction
     * applies to the next file, not the last one.
     */
    public void changeAgentOfRecord(UUID agentOfRecordId) {
        this.agentOfRecordId = agentOfRecordId;
    }
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
    public String getIssuanceBasis() { return issuanceBasis; }
    public String getIssuanceReason() { return issuanceReason; }
    public String getIssuedByName() { return issuedByName; }

    /** Record how this policy came to be issued (V24). Before the first save only. */
    public void recordIssuance(String basis, String reason, String issuerName) {
        this.issuanceBasis = basis;
        this.issuanceReason = reason == null || reason.isBlank() ? null : reason.strip();
        this.issuedByName = issuerName == null || issuerName.isBlank() ? null : issuerName;
    }

    /**
     * When the contract record was issued — the date on the document, not the date risk starts.
     *
     * <p>Split out of {@link #activate()} because the two stopped coinciding when cover began
     * waiting for the first premium. Every policy has an issue date from the moment it exists,
     * including one that is still an unpaid offer and one that is never taken up: the surrender
     * charge banding, distribution's months-since-issue clawback test and reinsurance's treaty
     * selection all read it, and none of them can wait for a payment that may never arrive.
     */
    public void recordIssuedOn(LocalDate issueDate) {
        this.issueDate = issueDate;
    }

    /**
     * Put the contract on risk.
     *
     * <p>Takes no date on purpose. It used to stamp the issue date as a side effect, which was
     * harmless only while activation and issuance were the same instant. They are not any more —
     * activation now follows the first premium — and leaving it would have quietly rewritten the
     * issue date to the payment date, moving every policy's surrender-charge band and clawback
     * window along with it.
     */
    public void activate() {
        if (!"PROPOSED".equals(status)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " cannot be issued from status " + status);
        }
        this.status = "ACTIVE";
    }

    /**
     * The offer expired unpaid.
     *
     * <p>Terminal, and deliberately not LAPSED: lapsing is what happens to an in-force policy
     * whose premiums stop, and a contract that was never on risk does not belong in the lapse
     * figures.
     *
     * <p>Guarded on PROPOSED because expiring anything else would silently drop live cover. Note
     * that {@code policy.sweep_expired_offers()} sets this status with a raw UPDATE and so does
     * not pass through here — its {@code WHERE status = 'PROPOSED'} enforces the same rule at the
     * same moment, but the two are one invariant written twice, and a condition added here must
     * be added there.
     */
    public void markNotTakenUp() {
        if (!"PROPOSED".equals(status)) {
            throw new InvalidPolicyStateException(
                "Policy " + policyNumber + " is " + status + ", not an outstanding offer");
        }
        this.status = "NOT_TAKEN_UP";
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
        // PAID_UP is in force: the customer stopped paying but keeps (reduced) cover. Only the
        // premium stops, not the cover.
        return "ACTIVE".equals(status) || "REINSTATED".equals(status) || "PAID_UP".equals(status);
    }

    /**
     * The zone a policy's DATES are read in. Lapse and suspension are stamped as instants, and a
     * lapse at 01:00 in Dar es Salaam is still the previous day in UTC -- so a death on the day of
     * a lapse would be judged against the wrong date for three hours of every night. Named, not
     * {@code systemDefault()}, for the reason {@code OfferReminderDispatcher} gives for the same
     * constant: the answer must not depend on how the host happens to be configured.
     */
    private static final java.time.ZoneId CIVIL_ZONE = java.time.ZoneId.of("Africa/Dar_es_Salaam");

    /**
     * Was this contract providing cover on {@code day}? The one date-bounded answer on the
     * platform, and the question a claim actually asks.
     *
     * <p>{@link #isInForce()} answers something else -- whether cover is running TODAY -- and was
     * being asked in its place. That was wrong in both directions: a death years after a term
     * ended read as covered because nothing had changed the status, and a death that happened
     * before a lapse, reported after it, was refused because the status had changed. The second
     * is the commonest late death claim there is, because dunning lapses a deceased life's policy
     * on its own.
     *
     * <p>The upper bound is the maturity date: a term's cover ends as that date begins. Within the
     * term, a LAPSED or SUSPENDED contract was on risk until the day it lapsed or was suspended,
     * and EXPIRED was on risk for the whole term -- expiry records that the term closed, not that
     * cover failed.
     *
     * <p><b>The lower bound is the commencement date when one is recorded, and nothing when it is
     * not.</b> A deliberately backdated or future-dated contract carries a commencement, and cover
     * before it is no cover -- provable, so refused. A normally issued policy carries no
     * commencement (cover starts at activation, and the platform stamps no date for that), so there
     * is no recorded cover-start to refuse against; the issue date is administrative, not the
     * moment risk began, and asserting a boundary the platform does not truly record is exactly
     * what this class must not do. The console still advises on the issue date at intake. This is
     * unchanged from the behaviour before this method existed, which never lower-bounded at all.
     *
     * <p><b>Known limit.</b> A REINSTATED policy keeps its {@code lapsedAt} and records no
     * reinstatement date, so the gap between the two cannot be told apart from cover. It is
     * treated as on risk throughout, which is what the platform did before this method existed.
     */
    public boolean wasOnRiskOn(LocalDate day) {
        if (day == null) {
            return false;
        }
        if (commencementDate != null && day.isBefore(commencementDate)) {
            return false;
        }
        if (maturityDate != null && !day.isBefore(maturityDate)) {
            return false;
        }
        return switch (status) {
            case "ACTIVE", "REINSTATED", "EXPIRED", "PAID_UP" -> true;
            // Void from inception: there is no day on which this policy was on risk, which is the
            // whole difference between cancelling in the free-look window and surrendering.
            case "CANCELLED_FREE_LOOK" -> false;
            case "LAPSED" -> lapsedAt != null && day.isBefore(lapsedAt.atZone(CIVIL_ZONE).toLocalDate());
            case "SUSPENDED" -> suspendedAt != null
                && day.isBefore(suspendedAt.atZone(CIVIL_ZONE).toLocalDate());
            // A customer-surrendered policy was on risk until its effective date; a claim-terminated
            // one carries no such date (cover was discharged by the claim) and is off risk.
            case "SURRENDERED" -> surrenderEffectiveDate != null && day.isBefore(surrenderEffectiveDate);
            // PROPOSED and NOT_TAKEN_UP were never on risk; MATURED is closed.
            default -> false;
        };
    }

    /**
     * The last date a premium can fall due: start plus the premium-paying term, or the policy
     * term where no shorter paying term was agreed. Null where the contract does not term at
     * all -- whole life, an annually renewable scheme -- which billing reads as "no end".
     *
     * <p>Computed here and carried on {@code policy.PolicyIssued}, because billing may not read
     * this module's tables and must not re-derive a contractual date on its own.
     */
    public LocalDate premiumPayingUntil() {
        Integer months = premiumPayingTermMonths != null ? premiumPayingTermMonths : policyTermMonths;
        LocalDate start = commencementDate != null ? commencementDate : issueDate;
        if (months == null || start == null) {
            return null;
        }
        return start.plusMonths(months);
    }

    /** Whether {@link #expire()} would succeed on {@code today}. Asked first, for the reason {@link #canLapse()} gives. */
    public boolean canExpire(LocalDate today) {
        return maturityDate != null && !today.isBefore(maturityDate)
            && ("ACTIVE".equals(status) || "REINSTATED".equals(status) || "SUSPENDED".equals(status));
    }

    /**
     * The term ran out. Terminal: nothing reinstates an EXPIRED policy, because reinstatement
     * revives a LAPSED one and a term that has ended has nothing left to revive.
     *
     * <p>Not MATURED. A term policy that reaches its end pays nothing -- that is what term
     * insurance is -- while MATURED says a maturity benefit was settled. A policy that carries a
     * maturity benefit is never expired; the sweep that calls this skips it.
     *
     * <p>Already EXPIRED is a satisfied post-condition and returns silently, so a second drain
     * instance racing the first does nothing rather than failing.
     */
    public void expire(LocalDate today) {
        // Any terminal status is a satisfied post-condition -- MATURED and SURRENDERED as well as
        // EXPIRED -- so a policy that closed between the drain selecting it and this call is left
        // alone rather than throwing. Same shape as mature()/terminateForSettledClaim().
        if (isClosed()) {
            return;
        }
        if (!canExpire(today)) {
            throw new InvalidPolicyStateException("Policy " + policyNumber + " cannot expire on " + today
                + " (status " + status + ", maturity date " + maturityDate + ")");
        }
        this.status = "EXPIRED";
    }

    /** Whether {@link #makePaidUp} would succeed. In force or lapsed-with-value can convert. */
    public boolean canMakePaidUp() {
        return "ACTIVE".equals(status) || "REINSTATED".equals(status) || "LAPSED".equals(status);
    }

    /**
     * The customer stops paying and keeps reduced cover (guide §21.3). The sum assured drops to the
     * paid-up figure the service computed, and the status becomes PAID_UP -- in force, no premium
     * due. Accepted from ACTIVE, REINSTATED (still paying) and LAPSED (a non-forfeiture conversion
     * of a policy that fell into arrears but has value).
     *
     * <p>Reduces the aggregate's own sum assured directly, unlike {@link #restateSumAssured} which
     * refuses anything but a scheme: paid-up IS the sanctioned way an individual policy's sum
     * assured moves, so it is expressed here rather than routed around that guard.
     */
    public void makePaidUp(BigDecimal paidUpSumAssured) {
        if (!canMakePaidUp()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber
                + " must be ACTIVE, REINSTATED or LAPSED to be made paid-up (current: " + status + ")");
        }
        if (paidUpSumAssured == null || paidUpSumAssured.signum() <= 0) {
            throw new IllegalArgumentException("A paid-up sum assured must be positive, was: " + paidUpSumAssured);
        }
        this.sumAssuredAmount = paidUpSumAssured;
        this.status = "PAID_UP";
    }

    /** Whether a customer surrender would be accepted: in force, paid-up, or lapsed-with-value. */
    public boolean canSurrender() {
        return "ACTIVE".equals(status) || "REINSTATED".equals(status)
            || "PAID_UP".equals(status) || "LAPSED".equals(status);
    }

    /**
     * Customer surrender takes effect (step 1, task 4; user decision Q2 -- cover stops at approval).
     * The status becomes SURRENDERED and the effective date is recorded, so wasOnRiskOn can tell a
     * death before it (covered) from one on or after it (not). Distinct from
     * {@link #terminateForSettledClaim()}, which also reaches SURRENDERED but through a claim and
     * carries no surrender date.
     */
    public void surrender(LocalDate effectiveDate) {
        if (!canSurrender()) {
            throw new InvalidPolicyStateException("Policy " + policyNumber
                + " cannot be surrendered from status " + status);
        }
        this.status = "SURRENDERED";
        this.surrenderEffectiveDate = effectiveDate;
    }

    public LocalDate getSurrenderEffectiveDate() { return surrenderEffectiveDate; }

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
    /**
     * Whether the contract has ended -- MATURED, SURRENDERED or EXPIRED. Public so the service's
     * "suppress the repeat event" checks ask the aggregate rather than restate the list, which is
     * how adding EXPIRED would otherwise have announced a maturity that never happened.
     */
    public boolean isClosed() {
        return alreadyClosed();
    }

    private boolean alreadyClosed() {
        // EXPIRED too. A death during the term, settled after the term ended, discharges cover on
        // a policy that has already stopped being invoiced -- the goal closure exists for is
        // met, and throwing here would do it inside claims' settlement listener, after the money
        // had left. The policy keeps EXPIRED, which is the truer record of how it ended.
        return "MATURED".equals(status) || "SURRENDERED".equals(status) || "EXPIRED".equals(status)
            || "CANCELLED_FREE_LOOK".equals(status);
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
        // PAID_UP too (product step 2). It is in force -- wasOnRiskOn says so -- so it can be
        // claimed on and it can mature. Its absence here meant a paid-up endowment reaching the
        // end of its term could never be closed: mature() threw, and the maturity benefit the
        // customer had kept paying towards had nowhere to go.
        return "ACTIVE".equals(status) || "REINSTATED".equals(status) || "PAID_UP".equals(status)
            || "LAPSED".equals(status) || "SUSPENDED".equals(status);
    }
}
