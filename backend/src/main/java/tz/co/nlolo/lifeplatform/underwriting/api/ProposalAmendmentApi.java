package tz.co.nlolo.lifeplatform.underwriting.api;

import tz.co.nlolo.lifeplatform.party.api.IndividualRegistration;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Returned for amendment, amended, resubmitted (2026-10-20, the agents' prospect-to-proposal flow). The state machine
 * lives here, not in a screen:
 *
 * <pre>
 *   OPEN / IN_REVIEW    -- return (an underwriter, with reasons) -->  RETURNED
 *   RETURNED            -- resubmit (the agent)                   -->  OPEN   (same case, next round)
 *   DECIDED, POSTPONED  -- resubmit (the agent)                   -->  OPEN   (same case, next round)
 *   DECIDED, DECLINED   -- final: no amendment, no resubmission
 *   RETURNED            -- decide                                 -->  refused until resubmitted
 * </pre>
 *
 * Amendments -- the contract's terms and the client's own details -- are accepted only while the case is RETURNED or
 * POSTPONED, and every return, amendment and resubmission is kept as the case's history.
 *
 * <p>Who may act is the web layer's to resolve (this module has no Spring Security): the underwriter role returns,
 * and only an agent whose client the applicant is may amend and resubmit.
 */
public interface ProposalAmendmentApi {

    /** What the underwriter found, from the fixed list the agent reads it by (user, 2026-10-20). */
    enum ReturnCategory { PERSONAL_DETAILS, DOCUMENTS, COVER_OR_TERM, DISCLOSURES, BENEFICIARIES, WRONG_PRODUCT, OTHER }

    /** One reason: the category, what is wrong, and what to change -- the two texts in the underwriter's own words. */
    record ReturnReason(ReturnCategory category, String problem, String change) {}

    /** The contract as amended: every field the new value, null meaning "none", exactly as at opening. */
    record TermsAmendment(BigDecimal sumAssured, Integer requestedTermMonths, Integer premiumPayingTermMonths,
                          String premiumFrequency, LocalDate proposedCommencementDate) {}

    record FieldChange(String field, String before, String after) {}

    /** The decision a resubmission answered -- kept because the next decision overwrites the case's own columns. */
    record PreviousDecision(DecisionOutcome outcome, String reason, Instant decidedAt) {}

    enum EventKind { RETURNED, AMENDED, RESUBMITTED }

    record CaseEventView(UUID eventId, EventKind kind, int round, String actorSubject, List<ReturnReason> reasons,
                         List<FieldChange> changes, PreviousDecision previousDecision, String note, Instant at) {}

    /**
     * A proposal waiting on its agent: returned (with the latest reasons), postponed (with the decision's reason), or
     * declined (with its reason, and nothing to do but read it).
     *
     * @param actionable false only for a decline -- it is listed so the agent sees why, and is final
     */
    record NeedsAction(UnderwritingCaseView caseView, List<ReturnReason> reasons, String note, boolean actionable) {}

    /**
     * A case waiting on an underwriter, as it arrived (2026-10-20): when it was last submitted -- a resubmission counts
     * as arriving again -- which round it is in, and the agent who introduced the client.
     *
     * @param introducedBy the registering agent's name; null where staff or the customer registered the client
     */
    record Arrival(UnderwritingCaseView caseView, Instant lastSubmittedAt, int round, boolean resubmitted,
                   String introducedBy) {}

    /**
     * Open and in-review cases, the most recently arrived first -- so a proposal an agent has just corrected and sent
     * back is at the top, not wherever it was first opened. {@code resubmittedOnly} keeps those alone.
     */
    org.springframework.data.domain.Page<Arrival> arrivals(boolean resubmittedOnly,
                                                           org.springframework.data.domain.Pageable pageable);

    /** @throws UnderwritingValidationException with no reason, a reason missing its texts, or a case not OPEN/IN_REVIEW */
    UnderwritingCaseView returnForAmendment(UUID caseId, List<ReturnReason> reasons, String note, String returnedBy);

    /** @throws UnderwritingValidationException unless RETURNED or POSTPONED, or terms the product refuses */
    UnderwritingCaseView amendTerms(UUID caseId, TermsAmendment amendment, String amendedBy);

    /**
     * Correct the applicant's own record on a returned or postponed proposal (user, 2026-10-20: agents may, to save a
     * round trip). A changed name or date of birth on a VERIFIED client sends their KYC back to PENDING, so the
     * identity document is checked against the corrected details.
     */
    PartyDetailView amendApplicant(UUID caseId, IndividualRegistration corrected, String amendedBy);

    /** @throws UnderwritingValidationException unless RETURNED or POSTPONED (a decline is final) */
    UnderwritingCaseView resubmit(UUID caseId, String note, String resubmittedBy);

    /** The case's returns, amendments and resubmissions, oldest first. Empty for a case never returned. */
    List<CaseEventView> history(UUID caseId);

    /**
     * Returned and postponed proposals, and the 20 newest declined since {@code declinedSince}, for these applicants --
     * the agent's "Needs your action". Newest first.
     */
    List<NeedsAction> needsAction(Set<UUID> applicantPartyIds, Instant declinedSince);
}
