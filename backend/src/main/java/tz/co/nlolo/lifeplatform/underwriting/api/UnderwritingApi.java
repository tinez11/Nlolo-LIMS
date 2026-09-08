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
    record DecisionInput(DecisionOutcome outcome, BigDecimal loadingPercent, String reason) {}

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
}
