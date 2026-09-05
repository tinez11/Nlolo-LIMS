package tz.co.nlolo.lifeplatform.policy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface PolicyApi {

    record BeneficiaryInput(BeneficiaryType type, UUID partyId, String freeformDesignee, BigDecimal sharePercent, boolean revocable) {}

    /**
     * @param commencementDate when risk starts. Distinct from the issue date: a policy
     *     issued today may carry risk from next month.
     * @param policyTermMonths how long cover runs. Null for a product that does not
     *     term -- whole life, an annuity, an annually renewable group scheme.
     * @param premiumPayingTermMonths how long premiums are paid, which on a
     *     limited-payment policy is shorter than the cover term. Never longer.
     *
     * <p>There is deliberately no {@code maturityDate} here. The aggregate derives it
     * from commencement plus term in {@code Policy.applyTerm}, so there is exactly one
     * place in the system it is computed. A caller able to supply it is a caller able
     * to supply a wrong one, and {@code policy_maturity_matches_term} would then reject
     * a request that looked perfectly reasonable to whoever sent it.
     */
    record IssueRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                         BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                         BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                         UUID agentOfRecordId, List<BeneficiaryInput> beneficiaries, String reasonForManualIssue,
                         LocalDate commencementDate, Integer policyTermMonths, Integer premiumPayingTermMonths,
                         /* Whose life is insured. Null means the policyholder insures themselves,
                            which the aggregate resolves rather than storing -- see Policy.issueTo. */
                         UUID lifeAssuredPartyId) {

        /**
         * Pre-Build-2 issuance, with no term information.
         *
         * <p>An extra record constructor rather than a widened call at all 31
         * construction sites. Unlike the {@code default}-interface-method trap in
         * Build 1 §9.1, this is plain Java with no proxy in the way, so delegation is
         * safe.
         */
        public IssueRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                             UUID agentOfRecordId, List<BeneficiaryInput> beneficiaries,
                             String reasonForManualIssue) {
            this(policyholderPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency,
                premiumAmount, premiumCurrency, premiumFrequency, agentOfRecordId, beneficiaries,
                reasonForManualIssue, null, null, null, null);
        }

        /** Pre-Build-4b issuance: a term, but no separate life assured. */
        public IssueRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                             UUID agentOfRecordId, List<BeneficiaryInput> beneficiaries,
                             String reasonForManualIssue, LocalDate commencementDate,
                             Integer policyTermMonths, Integer premiumPayingTermMonths) {
            this(policyholderPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency,
                premiumAmount, premiumCurrency, premiumFrequency, agentOfRecordId, beneficiaries,
                reasonForManualIssue, commencementDate, policyTermMonths, premiumPayingTermMonths, null);
        }

        /** The life assured, resolving the self-insured default against the policyholder. */
        public UUID resolveLifeAssured() {
            return lifeAssuredPartyId != null ? lifeAssuredPartyId : policyholderPartyId;
        }

    }

    record EndorsementInput(String endorsementType, LocalDate effectiveDate, Map<String, Object> changes) {}

    // ---------------------------------------------------------------------------------
    // Group business.
    //
    // One master policy, many insured members. ABC Company buys Group Life for 500
    // employees: the COMPANY is the policyholder, the 500 employees are the lives, and a
    // member is NOT a policy of their own. Everything below hangs off one policy number.
    // ---------------------------------------------------------------------------------

    /** One band on a GRADED scheme: a staff category and what it is worth. */
    record GradeInput(String gradeCode, BigDecimal benefitAmount) {}

    /**
     * One life, on the opening schedule or joining later.
     *
     * @param gradeCode required on a GRADED scheme, and rejected on any other -- a grade
     *     on a flat scheme is a caller who believes something about the contract that is
     *     not true.
     * @param salaryAmount required on a SALARY_MULTIPLE scheme, and rejected on any other.
     * @param joinedOn when cover starts for this member. Null means the scheme's
     *     commencement date, which is what an opening-schedule row means.
     */
    record MemberInput(UUID memberPartyId, String gradeCode, BigDecimal salaryAmount, LocalDate joinedOn) {}

    /**
     * Issue a master group policy together with its scheme, grades and opening schedule.
     *
     * <p>Deliberately one call rather than issue-then-populate. The sum assured of a
     * scheme <b>is</b> the total of its members' cover, so a scheme issued empty has a sum
     * assured of zero -- which the {@code policy_sum_assured_positive} check rejects, and
     * rightly: a contract insuring nobody for nothing is not a policy. Issuing atomically
     * also means a failure part-way through the schedule leaves no half-populated scheme
     * for somebody to find and mistake for a complete one.
     *
     * <p>There is no {@code sumAssuredAmount} parameter for the same reason there is no
     * {@code maturityDate} on {@link IssueRequest}: it is derived from the opening
     * schedule in exactly one place, and a caller able to supply it is a caller able to
     * supply one that disagrees with the members underneath it.
     *
     * @param commencementDate when the scheme's risk starts. <b>May not be in the future
     *     in this slice</b> -- see {@link #issueGroupScheme}.
     * @param policyTermMonths null for the usual annually renewable scheme.
     */
    record IssueGroupSchemeRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                                    UUID agentOfRecordId,
                                    BenefitBasis benefitBasis, BigDecimal flatBenefitAmount,
                                    BigDecimal salaryMultiple, BigDecimal fclAmount, String currency,
                                    List<GradeInput> grades, List<MemberInput> openingSchedule,
                                    BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                                    LocalDate commencementDate, Integer policyTermMonths,
                                    String reasonForManualIssue) {}

    /**
     * Issue a scheme. The product must be a GROUP_LIFE product, and the opening schedule
     * must name at least one life.
     *
     * <p><b>Commencement may not be in the future here.</b> Backdated is fine and common
     * -- a schedule reaches the insurer weeks after cover started -- but a scheme
     * commencing next month would carry a sum assured its members do not yet contribute
     * to, and the stored total and the derived total would disagree for a month. Making
     * the whole total date-aware is the first thing the next slice should do; refusing
     * the case is the honest version of not having done it yet.
     *
     * @throws InvalidPolicyStateException if the product is not GROUP_LIFE, the schedule
     *     is empty, or commencement is in the future
     */
    GroupSchemeView issueGroupScheme(IssueGroupSchemeRequest request, String issuedBy);

    /**
     * A scheme's configuration and current totals.
     *
     * @throws PolicyNotFoundException if no such policy exists in this tenant
     * @throws InvalidPolicyStateException if the policy exists but is not a scheme --
     *     distinct from not-found on purpose, because "you are looking at an individual
     *     policy" and "there is no such policy" send a caller to different places
     */
    GroupSchemeView getGroupScheme(String policyNumber);

    /**
     * One page of a scheme's members, each with the benefit currently in force for them.
     *
     * @param status null for every member including those who have left. An exited member
     *     stays on the roll because a claim can arrive after somebody leaves.
     * @param q null or blank for no name search. A member row holds a party id and no name,
     *     so this is resolved through {@code PartyApi.partyIdsMatchingName} and applied as an
     *     id filter — searching a 500-life roll for one person is otherwise impossible
     *     without paging the whole schedule by eye.
     */
    Page<PolicyMemberView> listMembers(String policyNumber, MemberStatus status, String q,
                                        Pageable pageable);

    /**
     * Add one life to an existing scheme, valuing them against the scheme's basis and
     * testing them against its free cover limit.
     *
     * <p>Restates the master policy's sum assured in the same transaction, so the contract
     * total and the member schedule cannot disagree even for an instant.
     *
     * @throws InvalidPolicyStateException if the person is already an active member, the
     *     policy is not in force, or the input does not match the scheme's basis
     */
    PolicyMemberView addMember(String policyNumber, MemberInput member, String addedBy);

    PolicyView issuePolicy(UUID underwritingCaseId, IssueRequest request, String issuedBy);
    PolicyView applyEndorsement(String policyNumber, EndorsementInput request, String appliedBy);
    void replaceBeneficiaries(String policyNumber, List<BeneficiaryInput> beneficiaries, String changedBy);

    /**
     * Every policy that currently names {@code partyId} as a beneficiary.
     *
     * <p>The reverse of {@link #replaceBeneficiaries}'s direction, and previously unanswerable:
     * beneficiary rows were only ever read by policy number, so a person's exposure as a
     * beneficiary was stored and unreachable. Returns an empty list for a party named on nothing,
     * which is the common case and not an error.
     */
    List<BeneficiaryOfView> beneficiaryOf(UUID partyId);
    SurrenderQuoteView quoteSurrenderValue(String policyNumber);
    PolicyView getPolicy(String policyNumber);

    /**
     * {@code agentOfRecordIds} is null/empty for "no agent filter" (staff and customer callers);
     * a non-empty set restricts results to policies whose {@code agentOfRecordId} is one of the
     * given ids -- an agents-realm caller's own resolved hierarchy team (see
     * {@code DistributionApi.resolveAgentTeam}), computed by the controller, not this method.
     *
     * <p>{@code relatedPartyId} asks a different question from {@code policyholderPartyId}:
     * which policies is this person connected to in ANY recorded capacity -- owner, life
     * assured, or active named beneficiary. It exists for the claims desk, where the claimant
     * is frequently not the owner. See {@code PolicyRepository.search} for why the two filters
     * AND rather than merge.
     */
    Page<PolicyView> searchPolicies(UUID policyholderPartyId, UUID relatedPartyId, PolicyStatus status,
                                     Set<UUID> agentOfRecordIds, String q, Pageable pageable);

    /**
     * The policy numbers an agents-realm caller's own hierarchy team (itself plus its downline,
     * within {@code DistributionApi.resolveAgentTeam}'s depth cap) is entitled to see -- resolves
     * {@code callerPartyId}'s team via {@code DistributionApi} internally, so {@code claims} (which
     * has no distribution dependency of its own, only {@code policy::api}) can scope its own
     * "browse my book" claims list/detail through this single call rather than needing the
     * distribution dependency itself. Empty if the party is not an agent in this tenant.
     */
    Set<String> policyNumbersForAgentTeam(UUID callerPartyId);

    CoverageStatusView getCoverageStatus(String policyNumber, LocalDate asOf);
    boolean isPolicyInForce(String policyNumber, LocalDate asOf);

    /**
     * The policy's cash value, for {@code policyloan}'s forced-lapse shortfall test
     * ({@code docs/01-domain-map.md:224}: "loan balance plus interest exceeds cash value"). A
     * pure read -- no event, no charge applied. See {@link CashValueView} for why
     * {@link #quoteSurrenderValue} cannot serve this purpose.
     */
    CashValueView getCashValue(String policyNumber);

    UUID reserveLoanValue(String policyNumber, BigDecimal amount, String currency, Duration ttl);
    void confirmReservation(UUID reservationId);
    void releaseReservation(UUID reservationId);

    /** M5: releases encumbrance applied by a confirmed reservation whose downstream disbursement
     * subsequently failed. Deliberately NOT releaseReservation -- that method correctly refuses a
     * CONFIRMED reservation, since un-confirming is not what this is. This reverses the
     * encumbrance while leaving the reservation's own terminal CONFIRMED status as the historical
     * record of what happened. */
    void releaseEncumbrance(String policyNumber, BigDecimal amount, String currency);

    void suspendPolicy(String policyNumber, String reason, String suspendedBy);
    void resumeSuspendedPolicy(String policyNumber, String resumedBy);
    void lapsePolicy(String policyNumber, String lapsedBy);

    /**
     * Whether {@link #lapsePolicy} would succeed right now -- for a caller that must lapse a
     * policy as a side effect of its own work and cannot simply attempt it.
     *
     * <p>Attempting and catching is not an option across a {@code @Transactional} boundary: the
     * inner boundary marks the whole transaction rollback-only before the caller sees the
     * exception, so the caller's commit fails with {@code UnexpectedRollbackException} however
     * carefully it handles the failure. {@code policyloan}'s forced lapse hit precisely that.
     * Backed by {@code Policy.canLapse()}, which is the same predicate {@code lapse()} itself
     * guards on -- so this answer cannot drift from the action.
     */
    boolean isLapsable(String policyNumber);
    void reinstatePolicy(String policyNumber, String reinstatedBy);

    /** A MATURITY claim settled, or the policy reached term. Terminal; idempotent on repeat. */
    void markMatured(String policyNumber, String maturedBy);

    /** A DEATH/DISABILITY/CRITICAL_ILLNESS claim settled: coverage is discharged, no further
     * premium is due. Terminal; idempotent on repeat. */
    void terminateForSettledClaim(String policyNumber, UUID claimId, String terminatedBy);
}
