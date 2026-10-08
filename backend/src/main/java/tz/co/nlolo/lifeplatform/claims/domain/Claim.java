package tz.co.nlolo.lifeplatform.claims.domain;

import tz.co.nlolo.lifeplatform.claims.api.ClaimDetails;
import tz.co.nlolo.lifeplatform.claims.api.ClaimDeclineReason;
import tz.co.nlolo.lifeplatform.claims.api.ClaimStatus;
import tz.co.nlolo.lifeplatform.claims.api.ClaimType;
import tz.co.nlolo.lifeplatform.claims.api.ClaimValidationException;
import tz.co.nlolo.lifeplatform.claims.api.InvalidClaimStateException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Aggregate root for {@code claims.claim}. The seven-state machine declared in this plan's
 * header is enforced HERE and only here -- every transition below follows the idempotent-on-
 * repeat / reject-on-conflict shape of {@code payment.domain.DisbursementInstruction.markCompleted}:
 * a repeat of the SAME outcome is a silent no-op, a call from any other source state throws.
 *
 * <p>{@code details} is stored as JSONB (see {@code db-migrations/claims/V1:15}) and mapped
 * directly as the sealed {@link ClaimDetails} type, following the same
 * {@code @JdbcTypeCode(SqlTypes.JSON)} shape {@code policy.domain.Endorsement} uses for its own
 * JSONB {@code changes} column -- but typed as the polymorphic interface rather than
 * {@code Map<String,Object>}. Hibernate 6's JSON type support resolves the concrete subtype via
 * the Jackson {@code ObjectMapper} on the classpath, which already carries {@code ClaimDetails}'s
 * {@code @JsonTypeInfo}/{@code @JsonSubTypes} discriminator -- verified against a real Postgres
 * container in {@code ClaimDetailsJsonbSmokeTest} rather than merely compiling.
 */
@Entity
@Table(name = "claim", schema = "claims")
public class Claim {

    @Id
    @UuidGenerator
    @Column(name = "claim_id")
    private UUID claimId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    /**
     * The insured life this claim is for, on a group scheme (V5).
     *
     * <p>NULL on individual business, where the policy names the life itself -- so null here
     * means "the policy knows", never "unknown". Distinct from {@link #claimantPartyId}, which
     * is who is FILING: on a death claim that is the widow, not the deceased.
     */
    @Column(name = "policy_member_id")
    private UUID policyMemberId;

    @Column(name = "claimant_party_id", nullable = false)
    private UUID claimantPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim_type", nullable = false)
    private ClaimType claimType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ClaimStatus status = ClaimStatus.REGISTERED;

    @Column(name = "date_of_event", nullable = false)
    private LocalDate dateOfEvent;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    private ClaimDetails details;

    @Column(name = "approved_amount")
    private BigDecimal approvedAmount;

    @Column(name = "approved_currency")
    private String approvedCurrency;

    /**
     * Which policy-term exclusion a decline invoked, and the dates it was measured from.
     *
     * <p>All three together or none — {@code chk_claim_decline_reason_complete}. Null on every
     * ordinary decline (fraud, non-disclosure, an event outside cover), which are the
     * assessor's findings alone and need no window.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "decline_reason")
    private ClaimDeclineReason declineReason;

    @Column(name = "exclusion_cover_start")
    private LocalDate exclusionCoverStart;

    @Column(name = "exclusion_window_months")
    private Integer exclusionWindowMonths;

    /** Claims/V3 -- set ONLY at construction (registration is the single point a Claim comes
     * into existence, unlike settlementIdempotencyKey below which is set by a later transition).
     * Backs the partial unique index on (tenant_id, registration_idempotency_key) that
     * ClaimsApiImpl.registerClaim relies on to dedupe a repeated registration attempt instead of
     * creating a second Claim row for the same real-world event. May be null: rows created before
     * V3 existed, and any future creation path that omits it, are not duplicates of one another
     * just because they share the same (absent) key -- see V3's own comment. */
    @Column(name = "registration_idempotency_key")
    private String registrationIdempotencyKey;

    @Column(name = "settlement_idempotency_key")
    private String settlementIdempotencyKey;

    @Column(name = "settlement_failure_reason")
    private String settlementFailureReason;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected Claim() {}

    /**
     * Registers a new claim. Validates that {@code details}' own {@link ClaimDetails#claimType()}
     * matches {@code claimType} -- the invariant {@link ClaimDetails}'s javadoc requires ("a
     * DEATH claim can never carry DisabilityClaimDetails"), enforced here since this constructor
     * is the aggregate's single creation point.
     */
    public Claim(UUID tenantId, String policyNumber, UUID policyMemberId, UUID claimantPartyId,
                 ClaimType claimType, LocalDate dateOfEvent, ClaimDetails details, String createdBy,
                 String registrationIdempotencyKey) {
        if (details == null || details.claimType() != claimType) {
            throw new ClaimValidationException(
                "Claim details type " + (details == null ? "null" : details.claimType())
                    + " does not match claim type " + claimType);
        }
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.policyMemberId = policyMemberId;
        this.claimantPartyId = claimantPartyId;
        this.claimType = claimType;
        this.dateOfEvent = dateOfEvent;
        this.details = details;
        this.createdBy = createdBy;
        this.registrationIdempotencyKey = registrationIdempotencyKey;
    }

    /** First assessment submitted. REGISTERED or REOPENED -> UNDER_ASSESSMENT. */
    public void beginAssessment() {
        if (status == ClaimStatus.UNDER_ASSESSMENT) {
            return;
        }
        if (status != ClaimStatus.REGISTERED && status != ClaimStatus.REOPENED) {
            throw new InvalidClaimStateException(
                "Claim " + claimId + " is " + status + ", cannot begin assessment");
        }
        this.status = ClaimStatus.UNDER_ASSESSMENT;
    }

    /** Settlement decision approved. UNDER_ASSESSMENT -> APPROVED, or REGISTERED -> APPROVED for
     * MATURITY only (auto-approval, docs/03-aggregate-design.md:134 / Cl3). */
    /**
     * @param ceiling the most this claim may pay -- the member's covered amount on a group
     *     scheme, the policy's sum assured otherwise, both resolved by
     *     {@code PolicyApi.claimableCover} from the claim's OWN stored facts rather than from
     *     anything the caller supplied. INCLUSIVE: a death claim normally pays the whole of the
     *     cover, so an exclusive bound would refuse the commonest correct settlement here.
     *     Null means unbounded, which no production path uses.
     */
    public void approve(BigDecimal approvedAmount, String approvedCurrency, BigDecimal ceiling) {
        approve(approvedAmount, approvedCurrency, ceiling, false);
    }

    /**
     * @param zeroAllowed true only for an annuity's death claim (product step 5): a verified death that
     *                    pays nothing is a real outcome there, never a data-entry error.
     */
    public void approve(BigDecimal approvedAmount, String approvedCurrency, BigDecimal ceiling, boolean zeroAllowed) {
        if (status == ClaimStatus.APPROVED) {
            return;
        }
        boolean maturityAutoApproval = claimType == ClaimType.MATURITY && status == ClaimStatus.REGISTERED;
        if (status != ClaimStatus.UNDER_ASSESSMENT && !maturityAutoApproval) {
            throw new InvalidClaimStateException(
                "Claim " + claimId + " is " + status + ", cannot approve");
        }
        if (approvedAmount == null || approvedAmount.signum() < 0 || (approvedAmount.signum() == 0 && !zeroAllowed)) {
            throw new ClaimValidationException("Approved amount must be positive");
        }
        // Positive was the ONLY check here, on every policy. Nothing stopped one member's death
        // claim being approved for a 500-life scheme's entire total, or an individual claim for
        // more than the contract insures.
        if (ceiling != null && approvedAmount.compareTo(ceiling) > 0) {
            throw new ClaimValidationException("Approved amount " + approvedAmount
                + " exceeds the " + ceiling + " this claim is covered for");
        }
        this.status = ClaimStatus.APPROVED;
        this.approvedAmount = approvedAmount;
        this.approvedCurrency = approvedCurrency;
    }

    /** Settlement decision rejected. UNDER_ASSESSMENT -> REJECTED. Note MATURITY has no
     * auto-rejection counterpart: auto-approval is the only automatic transition. */
    public void reject() {
        if (status == ClaimStatus.REJECTED) {
            return;
        }
        if (status != ClaimStatus.UNDER_ASSESSMENT) {
            throw new InvalidClaimStateException(
                "Claim " + claimId + " is " + status + ", cannot reject");
        }
        this.status = ClaimStatus.REJECTED;
    }

    /**
     * Reject, recording WHICH policy-term exclusion was invoked and the dates it was measured
     * from.
     *
     * <p>A decline that cannot say which window it used, measured from when, is a decline
     * nobody can defend. These claims are disputed by a commercial counterparty with the loan
     * agreement in front of them, sometimes years later, and "the assessor believed it was
     * suicide" is not an answer — "the death was on 2027-02-03, cover started 2026-08-03, and
     * the suicide exclusion ran twelve months" is.
     */
    public ClaimDeclineReason getDeclineReason() { return declineReason; }
    public LocalDate getExclusionCoverStart() { return exclusionCoverStart; }
    public Integer getExclusionWindowMonths() { return exclusionWindowMonths; }

    public void rejectForExclusion(ClaimDeclineReason reason, LocalDate coverStart, int windowMonths) {
        reject();
        this.declineReason = reason;
        this.exclusionCoverStart = coverStart;
        this.exclusionWindowMonths = windowMonths;
    }

    /** ClaimSettlementRequested published. APPROVED -> SETTLEMENT_REQUESTED. */
    public void markSettlementRequested(String idempotencyKey) {
        if (status == ClaimStatus.SETTLEMENT_REQUESTED) {
            return;
        }
        if (status != ClaimStatus.APPROVED) {
            throw new InvalidClaimStateException(
                "Claim " + claimId + " is " + status + ", cannot request settlement");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ClaimValidationException("A settlement idempotency key is required");
        }
        this.status = ClaimStatus.SETTLEMENT_REQUESTED;
        this.settlementIdempotencyKey = idempotencyKey;
        this.settlementFailureReason = null; // a fresh attempt clears the previous failure
    }

    /**
     * An annuity death approved at zero (product step 5): APPROVED -> SETTLED with no payment, because
     * nothing is owed by the rail. Refused for any claim that has an amount to pay.
     */
    public void settleWithNothingToPay() {
        if (status != ClaimStatus.APPROVED || approvedAmount == null || approvedAmount.signum() != 0) {
            throw new InvalidClaimStateException("Claim " + claimId + " has money to pay, so it settles through the payment rail");
        }
        this.status = ClaimStatus.SETTLED;
    }

    /** payment.DisbursementCompleted. SETTLEMENT_REQUESTED -> SETTLED. Terminal unless reopened. */
    public void markSettled() {
        if (status == ClaimStatus.SETTLED) {
            return;
        }
        if (status != ClaimStatus.SETTLEMENT_REQUESTED) {
            throw new InvalidClaimStateException(
                "Claim " + claimId + " is " + status + ", cannot mark settled");
        }
        this.status = ClaimStatus.SETTLED;
    }

    /** payment.DisbursementFailed. SETTLEMENT_REQUESTED -> APPROVED, preserving the reason so the
     * claim lands on a staff retry worklist (docs/02-module-architecture.md:125) rather than being
     * silently retried. The decision itself still stands, so APPROVED is the correct resting
     * state, and the CHECK constraint offers no SETTLEMENT_FAILED value. */
    public void markSettlementFailed(String reason) {
        if (status != ClaimStatus.SETTLEMENT_REQUESTED) {
            return; // a redelivered failure after a manual retry already moved it on
        }
        this.status = ClaimStatus.APPROVED;
        this.settlementFailureReason = reason;
    }

    /**
     * CLAIMS_MANAGER reopen. REJECTED or SETTLED -> REOPENED (new evidence, or a dispute).
     * Deliberately does NOT clear approvedAmount: the prior decision stays on the record, and a
     * new decision overwrites it only when one is actually made.
     *
     * <p><b>DOCUMENTED LIMITATION (M6 final-review I6): reopening a SETTLED claim does not, and
     * currently cannot, reverse the policy closure that settlement caused.</b>
     * {@code claims.application.PaymentEventListener} closes the underlying policy on settlement
     * (MATURED for a MATURITY claim, SURRENDERED otherwise), and {@code policy.domain.Policy} has no
     * transition OUT of either terminal status -- {@code reinstate()} requires LAPSED. So a claim
     * reopened from SETTLED, and even subsequently re-REJECTED, leaves its policy permanently
     * closed: coverage stays discharged and billing stays stopped for a claim that is no longer
     * settled. Nothing here is broken by this -- the money really did move, and un-closing a policy
     * silently would be worse -- but the asymmetry is real and is recorded rather than left to be
     * rediscovered. Adding a policy-reversal path is deliberately a later-milestone decision: it
     * needs its own domain event, its own audit story, and an answer for the premiums that went
     * uninvoiced while the policy was closed. Do NOT add one as a drive-by fix.
     */
    public void reopen() {
        if (status == ClaimStatus.REOPENED) {
            return;
        }
        if (status != ClaimStatus.REJECTED && status != ClaimStatus.SETTLED) {
            throw new InvalidClaimStateException(
                "Claim " + claimId + " is " + status + ", only a REJECTED or SETTLED claim can be reopened");
        }
        this.status = ClaimStatus.REOPENED;
        // The decline it was rejected with is no longer its state (2026-10-08): chk_claim_decline_reason_only_when_rejected
        // refused the save, so a claim declined for the waiting period or an exclusion could never be reopened -- a 500.
        // The decision stays in the claim's audit trail; a fresh decision records its own reason. The window it cited
        // goes with it -- chk_claim_decline_reason_complete holds the three together.
        this.declineReason = null;
        this.exclusionCoverStart = null;
        this.exclusionWindowMonths = null;
    }

    public UUID getClaimId() { return claimId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getPolicyMemberId() { return policyMemberId; }
    public UUID getClaimantPartyId() { return claimantPartyId; }
    public ClaimType getClaimType() { return claimType; }
    public ClaimStatus getStatus() { return status; }
    public LocalDate getDateOfEvent() { return dateOfEvent; }
    public ClaimDetails getDetails() { return details; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public String getApprovedCurrency() { return approvedCurrency; }
    public String getRegistrationIdempotencyKey() { return registrationIdempotencyKey; }
    public String getSettlementIdempotencyKey() { return settlementIdempotencyKey; }
    public String getSettlementFailureReason() { return settlementFailureReason; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
}
