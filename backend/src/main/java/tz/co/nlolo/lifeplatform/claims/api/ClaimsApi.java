package tz.co.nlolo.lifeplatform.claims.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * {@code claims} module's whole published surface -- {@code omnichannel} depends on
 * {@code claims::api} and this interface is everything it (and any other consumer) can see.
 *
 * <p>Only {@link #registerClaim} and {@link #getClaim} have implementations as of this task;
 * the remaining methods are declared here because the module's full surface was fixed by this
 * plan's authoring, but their bodies are Task 5 ({@code submitAssessment}), Task 9
 * ({@code decideSettlement}, {@code reopenClaim}), and Task 10 ({@code attachEvidence},
 * {@code listEvidence}, {@code searchClaims}) -- see {@code ClaimsApiImpl} for the exact split.
 */
public interface ClaimsApi {

    /**
     * @param policyMemberId the insured life this claim is for. REQUIRED when the policy is a
     *     group scheme — a scheme insures many lives, and "somebody on GL-000123 died" cannot
     *     be assessed, valued or paid — and REJECTED on individual business, where the policy
     *     names the life itself. Both rules are enforced through
     *     {@code PolicyApi.claimableCover}, because {@code claims} cannot see a product
     *     category without breaking its own allowed-dependency list.
     *     <p>Distinct from {@code claimantPartyId}, which is who is FILING: the widow, not the
     *     deceased. Conflating the two is what left a group claim unable to say who died.
     */
    record RegisterClaimRequest(String policyNumber, UUID policyMemberId, UUID claimantPartyId,
                                 ClaimType claimType, LocalDate dateOfEvent, ClaimDetails details,
                                 /* The covered life who died, on a funeral plan (required there, refused
                                    elsewhere -- enforced through PolicyApi.claimableCover). */
                                 UUID coveredLifeId,
                                 /* Whether the death was accidental: a funeral plan may waive its waiting
                                    period for an accident (plan R7). Ignored off a funeral plan. */
                                 boolean accidental) {

        /** Every claim that is not on a funeral plan. */
        public RegisterClaimRequest(String policyNumber, UUID policyMemberId, UUID claimantPartyId,
                                    ClaimType claimType, LocalDate dateOfEvent, ClaimDetails details) {
            this(policyNumber, policyMemberId, claimantPartyId, claimType, dateOfEvent, details, null, false);
        }
    }

    /**
     * Record whether a funeral claim's death was accidental (plan R7) -- what the assessor learns, so it may
     * change until the claim is decided. Refused on a claim that is not on a funeral plan.
     */
    ClaimView recordAccidentalDeath(UUID claimId, boolean accidental, String recordedBy);

    ClaimView registerClaim(RegisterClaimRequest request, String idempotencyKey, String registeredBy);

    ClaimView getClaim(UUID claimId);

    /**
     * {@code policyNumbers} is null for "no agent filter" (staff and customer callers); a
     * non-null (possibly empty) set restricts results to claims filed against one of the given
     * policy numbers -- an agents-realm caller's own book of business, resolved by the controller
     * via {@code PolicyApi.policyNumbersForAgentTeam} (claims has no agentOfRecordId of its own,
     * only the policy it references, so this joins through policy rather than needing a
     * distribution dependency here).
     */
    Page<ClaimView> searchClaims(ClaimStatus status, UUID claimantPartyId, Set<String> policyNumbers, String q, Pageable pageable);

    /**
     * @param assessedBy the assessor's identity-provider subject -- what separation of duties
     *     compares against the decider, so it must be the stable identifier, never a name.
     * @param assessorName how to show that person, captured now from their token; nullable,
     *     since a caller with no display name must still be able to assess.
     */
    ClaimAssessmentView submitAssessment(UUID claimId, String findings, BigDecimal recommendedAmount,
                                          String recommendedCurrency, boolean fraudIndicator, String assessedBy,
                                          String assessorName);

    /**
     * This claim's assessments, newest first.
     *
     * <p><b>Assessments were write-only.</b> {@code submitAssessment} persisted them,
     * {@code countByClaimIdAndTenantId} counted them for the "APPROVED needs an assessment"
     * invariant, and {@code existsByClaimIdAndTenantIdAndAssessor} enforced separation of duties
     * — but nothing ever read one back, and
     * {@code findByClaimIdAndTenantIdOrderByCreatedAtDesc} sat in the repository with no caller
     * at all.
     *
     * <p>That is not a missing convenience. Separation of duties means the person who decides a
     * settlement is, by construction, NOT the person who assessed it — a different human in a
     * different session. Without this they could not see the findings, the recommended amount or
     * the fraud flag, so the one number they had to type was the one number nobody had shown
     * them. The ceiling then refused it after the fact.
     */
    List<ClaimAssessmentView> listAssessments(UUID claimId);

    /**
     * The most this claim may pay, resolved the same way the approval ceiling is.
     *
     * <p>Deliberately its own read rather than a field on {@link ClaimView}. The underlying
     * {@code PolicyApi.claimableCover} THROWS when a member was not covered on the date of
     * event, and {@code searchClaims} builds a {@code ClaimView} per row — so deriving it there
     * would let one unclaimable row break the entire claims queue. Here a failure is one panel
     * on one screen.
     *
     * @throws tz.co.nlolo.lifeplatform.policy.api.InvalidPolicyStateException if the claim's own
     *     facts no longer resolve to cover. The caller renders that; it must not be swallowed
     *     into a zero, which would read as "this claim pays nothing".
     */
    ClaimCoverView claimableCover(UUID claimId);

    void decideSettlement(UUID claimId, boolean approved, BigDecimal approvedAmount, String approvedCurrency,
                           String rejectionReason, String payeeRef, String idempotencyKey, String decidedBy);

    /**
     * Decide a claim, citing a policy-term exclusion as the reason for declining it.
     *
     * <p><b>The platform owns the dates; the assessor owns the finding.</b> It cannot decide
     * that a death was suicide — {@code causeOfDeath} is free text, and a credit-life borrower
     * has no health record because nobody below the free cover limit is underwritten. What it
     * does is refuse a reason whose window had already closed, and record which window was
     * invoked and the dates it was measured from.
     *
     * <p>The gate is the platform overruling a human assessor, which it does nowhere else. The
     * case for it: an expired exclusion is a <em>factual error, not a judgement</em> — the
     * death was fourteen months after cover started and the exclusion ran twelve, and no
     * amount of assessor expertise changes those dates. It is also the error least likely to be
     * noticed, because it produces a plausible-looking declined claim and costs the lender an
     * entire loan.
     *
     * @param declineReason null for every ordinary decline — fraud, non-disclosure, an event
     *     outside cover. Those are the assessor's findings alone and need no window.
     * @throws ClaimValidationException if the cited window was not open on the date of event,
     *     or a decline reason is supplied while approving
     */
    void decideSettlement(UUID claimId, boolean approved, BigDecimal approvedAmount, String approvedCurrency,
                           String rejectionReason, ClaimDeclineReason declineReason,
                           String payeeRef, String idempotencyKey, String decidedBy);

    void reopenClaim(UUID claimId, String reason, String reopenedBy);

    /**
     * @param uploadedBy the uploader's identity-provider subject -- the identity.
     * @param uploadedByName how to show that person, captured now from their token; nullable.
     */
    ClaimEvidenceView attachEvidence(UUID claimId, String documentRef, String description, String uploadedBy,
                                     String uploadedByName);

    List<ClaimEvidenceView> listEvidence(UUID claimId);
}
