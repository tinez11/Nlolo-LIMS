package tz.co.nlolo.lifeplatform.underwriting.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface UnderwritingApi {
    /**
     * @param agentOfRecordId who sold it, carried through to automatic issuance so commission
     *                        can accrue on the normal path. Null for a direct sale.
     */
    /**
     * Open a case for an applicant who is insuring themselves.
     *
     * <p>Kept so the twelve existing test fixtures need no change. <b>Abstract, not a
     * {@code default} method</b>: a default carries no annotation for Spring's proxy, so
     * its delegation would run outside the implementation's {@code @Transactional} — the
     * bug this codebase has now met twice (Build 1 §9.1, Build 3 §4).
     */
    UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId, String openedBy);

    /**
     * Open a case, recording who is actually insured and where the business came from.
     *
     * <p>A null {@link ProposalDetails#lifeAssuredPartyId()} means the applicant insures
     * themselves; the implementation resolves it rather than storing a null, so the
     * column is always answerable going forward.
     */
    UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId, BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId, ProposalDetails proposal, String openedBy);

    /**
     * Open the evidence case for ONE scheme member whose benefit exceeds the free cover limit.
     *
     * <p>Its own entry point because it is the one legitimate individual case on a GROUP_LIFE
     * or CREDIT_LIFE product: {@link #openCase} refuses those, since a group product proposed
     * as a single life was issued as a policy covering nobody. Here the product is the
     * scheme's and the question is only "may this person have the excess". Still refused for a
     * life assured who is not a person.
     */
    UnderwritingCaseView openMemberEvidenceCase(UUID memberPartyId, UUID productId, UUID productVersionId,
                                                BigDecimal benefitAmount, String currency,
                                                UUID agentOfRecordId, String policyNumber,
                                                UUID policyMemberId, String openedBy);

    /**
     * Open a case for a group scheme: an employer asking to cover a schedule of lives.
     *
     * <p>Group business used to skip this entirely — {@code POST /group-schemes} created the
     * policy, the scheme and every member in one call, on risk on return, with no case, no
     * assessment and no decision. Individual business had a queue, a person's decision and an
     * offer the customer accepts by paying; the flow insuring five hundred people at a time
     * was the unsupervised one.
     *
     * <p>No {@code sumAssuredAmount} parameter, and the column is left NULL. Valuing the
     * schedule needs {@code GroupBenefitCalculator}, which lives in {@code policy} and which
     * this module must not re-implement — two copies of benefit arithmetic are two copies that
     * can drift, and this is money. The figure appears when policy derives it at issuance.
     *
     * @throws UnderwritingValidationException if the schedule is empty, or the product is not
     *     GROUP_LIFE
     */
    UnderwritingCaseView openCase(UUID applicantPartyId, UUID productId, UUID productVersionId,
                                   UUID agentOfRecordId, GroupProposal proposal, String openedBy);

    /**
     * Group funeral schemes (2026-10-07): replace an undecided proposal's members and families with those in a CSV
     * file ({@code FuneralScheduleFile.HEADER}). All or nothing -- a file with any problem changes nothing and says
     * every problem.
     */
    GroupScheduleResult replaceGroupSchedule(UUID caseId, byte[] csv, String uploadedBy);
    /**
     * Record a piece of evidence against the case and recompute the engine's recommendation.
     *
     * <p><b>This does not decide the case.</b> It used to: it ran the rules engine and wrote
     * the verdict into the decision fields, which published {@code UnderwritingDecisionMade},
     * which issued a real policy. The engine is {@code SimpleRulesEngine}, whose own source
     * calls its thresholds "illustrative, not actuarially validated", and there was no
     * override, so a placeholder algorithm was the sole author of every underwriting decision
     * on the platform. Worse, it had no condition despite being named {@code decideIfPossible}
     * — the first assessment settled the case whatever type it was, so a clean medical put a
     * policy in force before anyone assessed the applicant's finances or occupation.
     *
     * <p>Use {@link #decide} to settle a case.
     *
     * @throws UnderwritingCaseAlreadyDecidedException if the case is decided and not POSTPONED
     */
    /**
     * Record the channel the sale came through and the branch it belongs to (IFRS 17 I2), both refdata codes.
     * Refused with {@link SaleFixedException} once the policy is issued.
     */
    UnderwritingCaseView recordSale(UUID caseId, String salesChannel, String branchCode, String recordedBy);

    /** The case's policy is issued: its channel and branch are fixed. Idempotent; a no-op for an unknown case. */
    void lockSale(UUID caseId);

    UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy);

    /**
     * What a person decided.
     *
     * @param loadingPercent required for {@code LOADED} and forbidden otherwise, mirroring the
     *     {@code chk_loading_only_when_loaded} CHECK.
     * @param reason the underwriter's own words. Required, because a decision nobody explained
     *     is one nobody can review — and on a DECLINED or POSTPONED case it is what the
     *     applicant is eventually told.
     */
    record DecisionInput(DecisionOutcome outcome, BigDecimal loadingPercent, String reason, boolean ageEvidenceConfirmed) {
        /** Every decision but an annuity's: age evidence is the annuity light path's only evidence. */
        public DecisionInput(DecisionOutcome outcome, BigDecimal loadingPercent, String reason) {
            this(outcome, loadingPercent, reason, false);
        }
    }

    /**
     * Record (or change, until decided) an annuity case's chosen form, frequency and joint life
     * (product step 5). Refused on any product that is not an annuity, and for a form or frequency
     * the case's version does not offer.
     */
    AnnuityChoice recordAnnuityChoice(UUID caseId, AnnuityChoice choice, String recordedBy);

    /** An annuity case's choice; empty for every other case, and for an annuity case not yet chosen. */
    java.util.Optional<AnnuityChoice> annuityChoice(UUID caseId);

    /**
     * Record (or change, until decided) a deferred annuity case's retirement age (product step 5 D2).
     * Refused on any version that is not a deferred annuity, outside the version's vesting window, and
     * for an age the applicant has already reached.
     */
    DeferredAnnuityChoice recordDeferredAnnuityChoice(UUID caseId, int retirementAge, String recordedBy);

    /** A deferred annuity case's choice; empty for every other case, and for one not yet chosen -- never a throw. */
    java.util.Optional<DeferredAnnuityChoice> deferredAnnuityChoice(UUID caseId);

    /**
     * Record (or replace, until decided) a funeral case's plan and dependants (family funeral cover). The
     * whole family is priced through the product's one funeral quote and refused in its words; the case's
     * sum assured must be the main member's benefit on the plan (plan R3). Refused on any other product.
     */
    FuneralApplication recordFuneralApplication(UUID caseId, String planCode, java.util.List<FuneralApplication.Life> dependants,
                                                String recordedBy);

    /** A funeral case's application with a fresh quote; empty for every other case -- never a throw. */
    java.util.Optional<FuneralApplication> funeralApplication(UUID caseId);

    /**
     * Record (or replace, until decided) a unit-linked case's fund split, premium and sum assured (product step 6).
     * Checked against the version's funds, premium floors and sum-assured multiples, and refused in their words.
     */
    UnitLinkedChoice recordUnitLinkedChoice(UUID caseId, UnitLinkedChoice choice, String recordedBy);

    /** A unit-linked case's choice; empty for every other case -- never a throw. */
    java.util.Optional<UnitLinkedChoice> unitLinkedChoice(UUID caseId);

    /**
     * Record a human underwriting decision, and publish {@code UnderwritingDecisionMade}.
     *
     * <p>The only thing that settles a case, and therefore the only thing that puts a policy in
     * force. Requires at least one assessment: a decision with no evidence behind it is not
     * underwriting.
     *
     * <p>{@code callerIsSeniorUnderwriter} is passed in rather than read here, because the
     * caller's identity belongs to the web layer and this module holds no Spring Security
     * dependency. The controller supplies it from the token's realm roles. It cannot be a
     * {@code @PreAuthorize} expression either way: whether a decision IS an override depends
     * on the case's current recommendation, which no static role expression can see.
     *
     * @throws UnderwritingCaseNotFoundException if no such case exists in this tenant
     * @throws UnderwritingCaseAlreadyDecidedException if the case is already decided
     * @throws UnderwritingValidationException if there is no evidence, the reason is blank, or
     *     the loading does not match the outcome
     * @throws SeniorUnderwriterApprovalRequiredException if the outcome departs from the
     *     recommendation and the caller is not a senior underwriter
     */
    UnderwritingCaseView decide(UUID caseId, DecisionInput decision, String decidedBy,
                                 boolean callerIsSeniorUnderwriter);
    UnderwritingCaseView getCase(UUID caseId);

    /**
     * Records what the applicant declared on a proposal form.
     *
     * <p>Recordable at any point in a case's life, including after a decision. That is not an
     * oversight: a non-disclosure usually comes to light when a claim is made, long after the
     * case was decided, and the point of this record is to be able to say what was asked and
     * answered at the time. Refusing late entries would push that evidence off the platform
     * entirely, which is where it already is.
     *
     * <p>Does NOT re-open or re-decide the case, and does not feed the rules engine. See
     * {@code MedicalDisclosure}'s javadoc for why rating on disclosures needs an actuary rather
     * than a guess.
     */
    MedicalDisclosureView recordDisclosures(UUID caseId, List<DisclosureAnswer> answers, String recordedBy);

    /** Every disclosure set on a case, oldest first. Empty when none were ever recorded. */
    List<MedicalDisclosureView> listDisclosures(UUID caseId);
    /**
     * @param applicantPartyId a single applicant, for the client register's underwriting panel;
     *                         null means no filter on that dimension.
     * @param applicantPartyIds the agent scope -- NULL for staff (no scope), an EMPTY set for an
     *                          agent who has registered nobody (scope to nothing, zero rows). The
     *                          caller resolves it via {@code PartyApi.partyIdsRegisteredBy}; a case
     *                          carries no agent reference of its own.
     */
    Page<UnderwritingCaseView> listCases(UnderwritingCaseStatus status, UUID applicantPartyId,
                                          Set<UUID> applicantPartyIds, Pageable pageable);
    void referToSeniorUnderwriter(UUID caseId);
    boolean checkContestability(UUID caseId, LocalDate asOfDate);

    /**
     * Record that automatic issuance failed on this decided case, or clear a failure that has
     * since been resolved (pass null).
     *
     * <p><b>Called by policy's issuance listener, which is the one caller this exists for.</b>
     * That listener runs AFTER_COMMIT, so when issuance throws there is nothing left to roll
     * back: the case is decided, and without this it looked identical to a case whose policy was
     * created. One such case sat as ACCEPT with no policy behind it until somebody happened to
     * ask why a customer had nothing — the only trace was a stack trace in a log.
     *
     * <p>Runs in its own transaction on the caller's side, because the transaction that tried to
     * issue has already rolled back by the time this is called.
     *
     * <p>This does not re-open, re-decide or otherwise move the case. The decision stands; what
     * is recorded is that the paperwork behind it did not happen.
     */
    void recordIssuanceFailure(UUID caseId, String reason);
}
