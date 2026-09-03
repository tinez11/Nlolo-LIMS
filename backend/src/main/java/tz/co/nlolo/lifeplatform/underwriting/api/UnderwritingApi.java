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
    UnderwritingCaseView submitAssessment(UUID caseId, AssessmentType assessmentType, String findings, BigDecimal riskScore, String assessedBy);
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
