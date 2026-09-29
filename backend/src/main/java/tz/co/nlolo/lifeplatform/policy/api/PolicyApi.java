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
                         UUID lifeAssuredPartyId,
                         /* Why this policy is being issued by hand. Null is the normal path: the
                            policy is an offer, and it stays PROPOSED until its first premium
                            clears. A non-null basis whose startsCoverImmediately() is true puts it
                            in force at once -- see IssuanceBasis for why only three of the five do. */
                         IssuanceBasis issuanceBasis) {

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
                reasonForManualIssue, commencementDate, policyTermMonths, premiumPayingTermMonths, null, null);
        }

        /**
         * Issuance with no issuance basis: the normal path, which now produces an offer rather
         * than cover.
         *
         * <p>Kept as its own constructor rather than widened at all 31 construction sites, on the
         * same reasoning as the two above. Null here is not "unknown" -- it is the positive
         * statement that this is ordinary new business going through the front door, and so must
         * wait for the money like any other.
         */
        public IssueRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency,
                             BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                             UUID agentOfRecordId, List<BeneficiaryInput> beneficiaries,
                             String reasonForManualIssue, LocalDate commencementDate,
                             Integer policyTermMonths, Integer premiumPayingTermMonths,
                             UUID lifeAssuredPartyId) {
            this(policyholderPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency,
                premiumAmount, premiumCurrency, premiumFrequency, agentOfRecordId, beneficiaries,
                reasonForManualIssue, commencementDate, policyTermMonths, premiumPayingTermMonths,
                lifeAssuredPartyId, null);
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
     * @param memberType PARTY names a registered party; FREEFORM names a person who is
     *     not one. Exactly one designation may be supplied -- see
     *     {@code chk_policy_member_exactly_one_designation}.
     * @param memberName required on FREEFORM, rejected on PARTY.
     * @param memberDateOfBirth optional even on FREEFORM: a group scheme does not rate on
     *     age, so a name and the scheme's basis already value the member. Credit life
     *     requires it separately, because it tests entry age against product bounds.
     * @param gradeCode required on a GRADED scheme, and rejected on any other -- a grade
     *     on a flat scheme is a caller who believes something about the contract that is
     *     not true.
     * @param salaryAmount required on a SALARY_MULTIPLE scheme, and rejected on any other.
     * @param joinedOn when cover starts for this member. Null means the scheme's
     *     commencement date, which is what an opening-schedule row means.
     */
    record MemberInput(MemberType memberType, UUID memberPartyId, String memberName,
                        LocalDate memberDateOfBirth, String gradeCode,
                        BigDecimal salaryAmount, LocalDate joinedOn,
                        /**
                         * The member key on an AMORTISING_LOAN scheme, and the only stable
                         * identity a freeform borrower has. Rejected on any other basis.
                         */
                        String loanAccountNumber,
                        /** Required on an AMORTISING_LOAN scheme, rejected on any other. */
                        LoanTerms loanTerms) {

        /** A member of any scheme but credit life, whose members are loans. */
        public MemberInput(MemberType memberType, UUID memberPartyId, String memberName,
                            LocalDate memberDateOfBirth, String gradeCode,
                            BigDecimal salaryAmount, LocalDate joinedOn) {
            this(memberType, memberPartyId, memberName, memberDateOfBirth, gradeCode,
                salaryAmount, joinedOn, null, null);
        }

        /**
         * A member who is a registered party -- the only kind that existed before
         * freeform members, and still the default reading of a bare party id.
         *
         * <p>Kept so that adding FREEFORM did not mean rewriting thirty-odd call sites to
         * say PARTY, which is what every one of them already meant. A caller that names a
         * party and nothing else is unambiguous, and spelling that out adds no
         * information.
         */
        public MemberInput(UUID memberPartyId, String gradeCode, BigDecimal salaryAmount,
                            LocalDate joinedOn) {
            this(MemberType.PARTY, memberPartyId, null, null, gradeCode, salaryAmount, joinedOn,
                null, null);
        }

        /** A borrower: a name on a lender's schedule, and the loan that insures them. */
        public static MemberInput borrower(String memberName, LocalDate memberDateOfBirth,
                                            String loanAccountNumber, LoanTerms loanTerms) {
            return new MemberInput(MemberType.FREEFORM, null, memberName, memberDateOfBirth,
                null, null, null, loanAccountNumber, loanTerms);
        }
    }

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
                                    String reasonForManualIssue,
                                    /**
                                     * Null for an ordinary offer: the scheme is issued PROPOSED
                                     * and the employer's first cleared premium starts cover,
                                     * exactly as an individual customer accepts by paying.
                                     *
                                     * <p>This REVERSES build5 §2.6 ("a scheme goes on risk at
                                     * issuance, outside offer-and-acceptance"). That was written
                                     * when POST /group-schemes was the only way a scheme could
                                     * exist and waiting for a premium would have meant nobody
                                     * was ever covered. Group business has a pipeline now.
                                     *
                                     * <p>A basis that already carries cover (MIGRATION,
                                     * CONVERSION, REINSTATEMENT) skips the wait, for the same
                                     * reason it does on an individual policy: the contract is in
                                     * force somewhere else already.
                                     */
                                    IssuanceBasis issuanceBasis,
                                    /**
                                     * How this lender's loans repay principal. Required on
                                     * AMORTISING_LOAN and rejected on every other basis.
                                     */
                                    InterestMethod interestMethod,
                                    /**
                                     * How often this lender's loans repay. Required on
                                     * AMORTISING_LOAN and rejected on every other basis.
                                     * On the scheme because a lender's product repays on
                                     * one cadence.
                                     */
                                    RepaymentFrequency repaymentFrequency,
                                    /**
                                     * Percent PER ANNUM of each borrower's original principal,
                                     * charged once at enrolment. 0.5000 means 0.5%.
                                     *
                                     * <p>On the SCHEME rather than the product because the rate
                                     * is negotiated per lender -- 0.4% for one, 0.5% for another,
                                     * on the same filed product. A product-level rate would force
                                     * a duplicate product, and its own TIRA filing, per lender.
                                     *
                                     * <p>Required on AMORTISING_LOAN and rejected on every other
                                     * basis, like {@code interestMethod} above it.
                                     */
                                    BigDecimal premiumRatePercent,
                                    /**
                                     * What that rate MEANS, which the rate alone does not say.
                                     *
                                     * <p>Both real client schedules price on the full disbursed
                                     * amount and agree about nothing else: one charges the rate
                                     * flat whatever the term, the other once per policy year on
                                     * the balance still outstanding. A single formula matched
                                     * neither, and was out by half on a two-month loan.
                                     *
                                     * <p>Required on AMORTISING_LOAN and rejected elsewhere, like
                                     * the rate it qualifies. Never defaulted: a default prices one
                                     * lender on another's agreement without saying so.
                                     */
                                    CreditLifePremiumBasis premiumBasis) {

        /** A credit-life scheme, which states both how and how often its loans repay. */
        public IssueGroupSchemeRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                                        UUID agentOfRecordId,
                                        BenefitBasis benefitBasis, BigDecimal flatBenefitAmount,
                                        BigDecimal salaryMultiple, BigDecimal fclAmount, String currency,
                                        List<GradeInput> grades, List<MemberInput> openingSchedule,
                                        BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                                        LocalDate commencementDate, Integer policyTermMonths,
                                        String reasonForManualIssue, IssuanceBasis issuanceBasis,
                                        InterestMethod interestMethod, BigDecimal premiumRatePercent,
                                        CreditLifePremiumBasis premiumBasis) {
            this(policyholderPartyId, productId, productVersionId, agentOfRecordId, benefitBasis,
                flatBenefitAmount, salaryMultiple, fclAmount, currency, grades, openingSchedule,
                premiumAmount, premiumCurrency, premiumFrequency, commencementDate, policyTermMonths,
                reasonForManualIssue, issuanceBasis, interestMethod,
                interestMethod == null ? null : RepaymentFrequency.MONTHLY,
                premiumRatePercent, premiumBasis);
        }

        /** Any scheme but credit life, which is the only basis that has an interest method. */
        public IssueGroupSchemeRequest(UUID policyholderPartyId, UUID productId, UUID productVersionId,
                                        UUID agentOfRecordId,
                                        BenefitBasis benefitBasis, BigDecimal flatBenefitAmount,
                                        BigDecimal salaryMultiple, BigDecimal fclAmount, String currency,
                                        List<GradeInput> grades, List<MemberInput> openingSchedule,
                                        BigDecimal premiumAmount, String premiumCurrency, String premiumFrequency,
                                        LocalDate commencementDate, Integer policyTermMonths,
                                        String reasonForManualIssue, IssuanceBasis issuanceBasis) {
            this(policyholderPartyId, productId, productVersionId, agentOfRecordId, benefitBasis,
                flatBenefitAmount, salaryMultiple, fclAmount, currency, grades, openingSchedule,
                premiumAmount, premiumCurrency, premiumFrequency, commencementDate, policyTermMonths,
                reasonForManualIssue, issuanceBasis, null, null, null, null);
        }
    }

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
     * As above, also recording WHAT the scheme was issued on and BY WHOM (policy V24).
     *
     * @param underwritingCaseId the decided group case this scheme came from, or null on the
     *     exception route. Recorded on the scheme, and a second scheme from one case is refused
     *     ({@link PolicyAlreadyIssuedForCaseException}) -- the same one-case-one-policy rule an
     *     individual policy has always had, and schemes never did.
     * @param issuedByName the issuer's display name from their token: the underwriter of record
     *     for a scheme set up from agreed terms. Null for automatic issuance.
     */
    GroupSchemeView issueGroupScheme(IssueGroupSchemeRequest request, String issuedBy,
                                     UUID underwritingCaseId, String issuedByName);

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
    /**
     * One member of a scheme, by id -- built exactly as {@link #listMembers} builds a row.
     *
     * <p>For a screen that holds a member id and needs the person: finance's transfer queue
     * knows a claim, and the claim knows only the member's id. The roll could be searched by
     * name or reference but not by id, so the queue could say which claim it paid but not whose.
     *
     * @throws InvalidPolicyStateException if the policy is not a scheme or the member is not on it
     */
    PolicyMemberView getMember(String policyNumber, UUID policyMemberId);

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

    /**
     * Move a scheme's free cover limit, restating every member's cover against the new one.
     *
     * <p><b>Raising it is the case this exists for.</b> A limit is typed once at set-up, and a
     * wrong one covers every borrower for a fraction of their loan and opens an underwriting case
     * for each of them. Until this, the only remedy was a second scheme.
     *
     * <p><b>An amendment may not REDUCE anybody's cover</b>, and is refused naming how many
     * members it would. Lowering a limit below live cover would leave borrowers part-uninsured
     * from a date nobody told them about, and on a book of several hundred it would mint an
     * underwriting case per person. Lowering is allowed where it caps nobody who is not already
     * capped — which is the honest half of the case, since it only binds members yet to come.
     *
     * <p>{@code newLimit} null means the scheme has no limit at all: a real design, and never the
     * same as zero.
     *
     * @return the scheme as it now stands, with its restated total
     */
    GroupSchemeView amendFreeCoverLimit(String policyNumber, java.math.BigDecimal newLimit,
                                         String reason, String amendedBy);

    /** What recording a member's evidence decision did. */
    enum MemberEvidenceResult {
        /** The excess was granted: covered up to the full benefit from today. */
        GRANTED,
        /** The excess was refused: covered up to the free cover limit, as before. */
        REFUSED,
        /** Nothing to decide any more -- the member left, or a raised limit already covers them. */
        NOT_NEEDED
    }

    /**
     * Apply an underwriter's decision on a scheme member's free-cover-limit evidence case.
     *
     * <p>Until this existed an accepted evidence case issued the member a separate single-life
     * policy on the scheme's product, and the member's own record stayed EVIDENCE_REQUIRED with
     * the excess never granted; a decline recorded nothing. Granting writes a NEW effective-dated
     * benefit row -- what the member was covered for yesterday is what a claim dated yesterday
     * pays -- restates the scheme total and tells regreporting. Refusing leaves cover at the limit
     * and records the decision. Either way an endorsement says who decided and on which case.
     *
     * <p>The premium does not move: a scheme's premium was agreed for its schedule and changes at
     * renewal, which is why underwriting refuses a loading on an evidence case.
     *
     * @return {@link MemberEvidenceResult#NOT_NEEDED} when the member is no longer waiting on this
     *     case -- exited, or brought within a raised limit -- in which case nothing is written
     * @throws InvalidPolicyStateException if the member is not on this scheme
     */
    MemberEvidenceResult recordMemberEvidenceDecision(String policyNumber, UUID policyMemberId, UUID caseId,
                                                      boolean accepted, String decidedBy);

    /**
     * The scheme member an evidence case was opened for, found from the member's side. For cases
     * opened before underwriting V11 recorded it on the case itself; empty if no member (still)
     * points at the case.
     */
    java.util.Optional<MemberRef> findMemberAwaitingEvidence(UUID caseId);

    /** A scheme member, by the two ids that name one. */
    record MemberRef(String policyNumber, UUID policyMemberId) {}

    /**
     * Take one life off a scheme, for a reason other than a claim the insurer paid.
     *
     * <p>Until credit life, the only way off a scheme was
     * {@link #dischargeForSettledClaim}: the insurer paid and the life left. Most loans that
     * end early end because they were settled, refinanced, cancelled or written off — and
     * without this, a repaid borrower stays insured for a debt that no longer exists, and no
     * refund or clawback can ever fire because nothing tells the platform anything happened.
     *
     * <p>Shares its mechanics with the claim path exactly: the same exit, the same restatement
     * of the scheme total, the same {@code policy.GroupMemberExited} event, and the same
     * closure of a scheme whose last life has gone. Exiting is one operation with several
     * reasons, not several operations.
     *
     * <p><b>Dated to the EVENT, not to the processing run.</b> A loan settled in March and
     * reported in June stopped being covered in March; a late exits file is entirely ordinary,
     * and dating the exit to when we heard about it would keep a repaid borrower in the
     * scheme's sum assured for three months.
     *
     * <p><b>Idempotent.</b> Exiting an already-exited member changes nothing and does not
     * throw: a lender can legitimately resend a corrected exits file, and a second exit that
     * subtracted the cover again would understate the scheme by a whole borrower. The first
     * exit stands — a repeat never overwrites when or why they left.
     *
     * @param exitDate when cover ended. May not precede the member joining.
     * @param reason required; a refund and a clawback both branch on it
     * @param outstandingBalanceAtExit the lender's own figure, recorded and not trusted. Null
     *     for a member with no loan.
     * @throws InvalidPolicyStateException if the member is not on this scheme, the reason is
     *     absent, or the date precedes the member joining
     */
    PolicyMemberView exitMember(String policyNumber, UUID policyMemberId, LocalDate exitDate,
                                 ExitReason reason, BigDecimal outstandingBalanceAtExit,
                                 String exitedBy);

    /**
     * Turn a freeform member into a real, identified person.
     *
     * <p>A credit-life member is enrolled FREEFORM — a name and a date of birth off a lender's
     * CSV, because no lender sends a national ID and party de-duplication cannot fire without
     * one (spec §2.2). That is correct for four hundred rows a month, and it stops being
     * correct at exactly one moment: <b>the claim</b>. The platform is about to pay out against
     * this person, and "who died" cannot be a string in a spreadsheet cell.
     *
     * <p><b>An existing party is reused, never duplicated.</b> The borrower may already bank
     * with the lender, and two party rows for one national ID is precisely the duplicate-person
     * problem the party module's identity index exists to prevent.
     *
     * <p><b>The loan and the reference survive untouched.</b> Cover is measured against the loan
     * columns, so a promotion that dropped them would leave a member nobody can value; and the
     * name the lender used survives too, because their monthly file will keep arriving calling
     * this borrower the same thing and it is the only document they have.
     *
     * <p>Idempotent: a claim is registered, assessed, possibly reopened and settled, and
     * promoting an already-promoted member returns them unchanged rather than minting a second
     * person each time somebody touches it.
     *
     * @throws InvalidPolicyStateException if the member is not on this scheme, is already a
     *     registered party rather than a freeform one, or the request carries no identity
     *     document — promoting without one registers a second unidentified person, which looks
     *     resolved and is not.
     */
    PolicyMemberView promoteMember(String policyNumber, UUID policyMemberId,
                                    PromoteMemberRequest identity, String promotedBy);

    PolicyView issuePolicy(UUID underwritingCaseId, IssueRequest request, String issuedBy);

    /** As above, naming the issuer from their token (policy V24) -- see issueGroupScheme's overload. */
    PolicyView issuePolicy(UUID underwritingCaseId, IssueRequest request, String issuedBy, String issuedByName);
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
     * The exclusion windows this policy was issued under, in months from cover start.
     *
     * <p>Exists because CLAIMS MAY NOT DEPEND ON PRODUCT -- its allowed dependencies are
     * policy, underwriting, party, document and refdata, and one product type in claims
     * bytecode is a module violation ModularityTests rejects. Policy already depends on
     * product and knows which version a policy was issued on, so it answers on claims behalf.
     *
     * <p>A dedicated read rather than fields on PolicyView: that view is every row of several
     * list endpoints, and resolving the product version per row would be a query each to
     * answer a question only the decline path asks.
     *
     * @param policyMemberId the member whose cover start is wanted; null on individual
     *     business, where the policy commencement is the answer.
     */
    ExclusionPeriodsView exclusionPeriodsFor(String policyNumber, java.util.UUID policyMemberId);

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

    /**
     * Start cover, because the first premium has cleared.
     *
     * <p>Idempotent and silent on anything that is not an outstanding offer: a second
     * {@code billing.PremiumCollected} is the ordinary second month, and a policy that reached
     * ACTIVE through a MIGRATION issuance never had an offer to accept. Re-publishing
     * {@code policy.PolicyActivated} would double-accrue commission and double-cede the risk, so
     * the guard is not a nicety.
     */
    void activateOnFirstPremium(String policyNumber);

    /**
     * Close an offer nobody took up, and say so out loud.
     *
     * <p>Idempotent and silent on anything that is not an outstanding offer, for the same reasons
     * {@link #activateOnFirstPremium} is.
     *
     * <p>This exists alongside {@code policy.sweep_expired_offers()} rather than instead of it.
     * The sweep is what enforces the deadline across every tenant on a schedule, and it sets the
     * status with a raw UPDATE — SQL cannot publish a Spring event, so for as long as it was the
     * only path an expired offer changed state in total silence. That was tolerable while nothing
     * consumed it. It stopped being tolerable when a customer needed telling they are not
     * insured, which is the one message in the offer lifecycle somebody will act on.
     */
    void expireOffer(String policyNumber);

    /** A MATURITY claim settled, or the policy reached term. Terminal; idempotent on repeat. */
    void markMatured(String policyNumber, String maturedBy);

    /**
     * What a claim against this contract may pay, for this life, as at {@code asOf}.
     *
     * <p><b>As at the date of event, not today.</b> {@code policy_member_benefit} is
     * effective-dated for exactly this reason: a death two years ago must be valued at the
     * cover in force then, not at a benefit restated at a renewal since.
     *
     * <p>Answered here rather than in {@code claims} because the member schedule is this
     * module's, and because claims cannot see {@code ProductCategory} without breaking its own
     * allowed-dependency list. A caller gets one number and does not learn what kind of
     * contract produced it.
     *
     * <p>On a group scheme this returns the member's {@code covered_amount} — the FCL-capped
     * figure where the limit bit — and NOT the scheme's sum assured, which is 500 people's
     * cover added together and is nobody's claim.
     *
     * @param policyMemberId REQUIRED on a policy that has a member schedule and REJECTED on one
     *     that does not. A scheme with no member named cannot be valued; a member named against
     *     an individual policy is a caller who believes that contract has a schedule.
     * @throws InvalidPolicyStateException if the member is missing, supplied where it does not
     *     belong, not a member of this scheme, or was not covered on {@code asOf}
     * @throws PolicyNotFoundException if no such policy exists in this tenant
     */
    /**
     * The cover a claim of this benefit type can be valued at.
     *
     * <p>{@code benefitType} is a {@code String} because claims may not reference
     * {@code product.api.BenefitType} without failing {@code ModularityTests} — the same reason
     * {@code PolicyView.productCategory} is one, and the reason this view exists at all. Callers
     * pass {@code claimType.name()}; the four {@code ClaimType} values map one-to-one onto benefit
     * types.
     *
     * <p><b>Before this parameter existed the method returned the policy's single sum assured for
     * every claim</b>, so a critical-illness claim was valued at the full death benefit — a
     * survivable condition paying the whole cover, on a rider nobody had costed.
     *
     * @throws InvalidPolicyStateException if the policy has no active coverage for that benefit
     */
    ClaimableCoverView claimableCover(String policyNumber, UUID policyMemberId, LocalDate asOf,
                                       String benefitType);

    /**
     * A DEATH/DISABILITY/CRITICAL_ILLNESS claim settled: cover is discharged.
     *
     * <p><b>What that discharges depends on the contract, which is why this is no longer called
     * "terminate".</b> On individual life the single insured life is dead, the contract is over
     * and billing must stop invoicing it — the policy goes SURRENDERED. On a group scheme it
     * discharges ONE MEMBER: they leave the schedule, the scheme total is restated without them,
     * and the master policy is untouched, because the other lives are alive and insured and the
     * employer still owes premium for them.
     *
     * <p>The old name said "terminate" and the old body did exactly that on both, so one
     * member's settled death claim surrendered the whole scheme and silently uninsured the
     * workforce. A verb that described only half the cases is part of how that read as correct.
     *
     * @param policyMemberId which life, on a scheme. Ignored on individual business, where the
     *     policy names the life itself.
     * @param dateOfEvent when cover for that life ended. A member's exit is dated to THIS, not
     *     to the day the payment cleared: a death in March settled in September means they
     *     stopped being covered in March, and dating it to September would leave them counted
     *     in the scheme total for six months they were not alive.
     * @implNote idempotent on repeat, on both branches — a redelivered DisbursementCompleted
     *     must not publish a second event or re-exit an already-exited member.
     */
    void dischargeForSettledClaim(String policyNumber, UUID policyMemberId, LocalDate dateOfEvent,
                                   UUID claimId, String dischargedBy);

    /**
     * Claims telling policy that a death claim on this member is open -- registered, or reopened
     * from a rejection -- and not yet paid.
     *
     * <p>Policy cannot see claims: claims depends on policy, never the reverse. So between a death
     * being reported and its claim being paid, the member read as an ordinary live loan, and an
     * exits file could take them off cover first with the wrong reason and a refund a death never
     * earns. Recording it here lets the roll say so and lets the exit paths refuse (V23).
     *
     * <p>A no-op for a member who has already left: a late claim for somebody who died while
     * covered is legitimate, and their exit is already on the record.
     *
     * @throws InvalidPolicyStateException if the member is not on this scheme
     */
    void recordOpenDeathClaim(String policyNumber, UUID policyMemberId, UUID claimId);

    /**
     * Correct who earns commission on a scheme, from now on.
     *
     * <p>On credit life the commission belongs to the lender (spec 2.8), but a scheme could only
     * ever get its agent at issuance -- and there, whoever registered the lender's party won. So
     * GRP-B6CE9639 came to carry an individual agent, with no way to put it right.
     *
     * <p>Forward only: accruals already booked stay with whoever earned them. Publishes
     * {@code policy.AgentOfRecordChanged}, which distribution follows and audit records.
     *
     * @param agentOfRecordId a real agent in this tenant, or null for a direct scheme
     * @throws UnknownAgentOfRecordException if it names no agent
     * @throws InvalidPolicyStateException if the policy is not a scheme
     */
    PolicyView changeSchemeAgentOfRecord(String policyNumber, UUID agentOfRecordId, String reason, String changedBy);

    /** The death claim was rejected. Clears only THAT claim; a no-op if another is recorded. */
    void clearOpenDeathClaim(String policyNumber, UUID policyMemberId, UUID claimId);
}
