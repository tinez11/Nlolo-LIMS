package tz.co.nlolo.lifeplatform.underwriting.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * M3 addition: productVersionId/sumAssuredAmount/sumAssuredCurrency were not exposed here in
 * M2 because nothing needed them yet -- UnderwritingCase (the domain entity) has always stored
 * all three. policy.application.UnderwritingDecisionEventListener needs all three from a
 * decided case to issue a policy; the UnderwritingDecisionMade event payload alone
 * (caseId, outcome, loadingPercent, decidedAt per api/asyncapi-events.yaml) does not carry
 * them. Additive -- the sole existing construction site (UnderwritingApiImpl.toView) is
 * updated in the same commit as this file.
 *
 * <p>{@code @JsonIgnore} on the three new components: discovered via a real regression run,
 * not anticipated by the plan that specified this record -- underwriting.infrastructure.
 * UnderwritingController's openCase/getCase/submitAssessment endpoints all return this record
 * directly as their JSON response body (there is no separate wire DTO), so without these
 * annotations the three new fields leak straight into the HTTP response and violate
 * openapi-underwriting.yaml's additionalProperties:false schema for the case response --
 * exactly what UnderwritingContractTest caught (a real OpenApiValidationException, not a
 * hypothetical one). Ignoring them for JSON serialization only; every internal, in-process
 * caller (this task's UnderwritingDecisionEventListener chief among them) still reads them via
 * the normal record accessor methods, unaffected by a Jackson-only annotation.
 */
public record UnderwritingCaseView(UUID caseId, UUID applicantPartyId, UUID productId, @JsonIgnore UUID productVersionId,
                                    UnderwritingCaseStatus status, ReferralStatus referralStatus, DecisionOutcome decisionOutcome,
                                    BigDecimal decisionLoadingPercent, String decisionDeclineReason, Instant decisionDecidedAt,
                                    @JsonIgnore BigDecimal sumAssuredAmount, @JsonIgnore String sumAssuredCurrency,
                                    UUID agentOfRecordId,
                                    // Proposal identity (V4). These ARE serialized, so
                                    // openapi-underwriting.yaml's UnderwritingCaseView schema grew to
                                    // match -- it declares additionalProperties: false, which is what
                                    // caught the last set of fields added here without a spec change.
                                    String proposalNumber, UUID lifeAssuredPartyId,
                                    String branch, String sourceOfBusiness,
                                    LocalDate proposedCommencementDate,
                                    // The rules engine's advice (V5), and who acted on it.
                                    // Serialized, and openapi-underwriting.yaml's schema grew to
                                    // match for the same additionalProperties:false reason as the
                                    // proposal block above.
                                    //
                                    // The console renders the recommendation beside the decision
                                    // form so an underwriter is not starting from a blank page,
                                    // and needs decisionOverrodeRecommendation to mark a decision
                                    // that departed from it -- which only a senior underwriter may
                                    // make. recommendationAt is deliberately NOT exposed: nothing
                                    // reads it, and the case's own timeline already carries when
                                    // each assessment arrived.
                                    DecisionOutcome recommendationOutcome,
                                    BigDecimal recommendationLoadingPercent,
                                    String recommendationReason,
                                    String decisionDecidedBy,
                                    boolean decisionOverrodeRecommendation,
                                    // What the applicant asked for about the contract (V6).
                                    // Serialized: the console prefills the manual issue form
                                    // from them, and the case detail shows what was proposed.
                                    Integer requestedTermMonths,
                                    Integer premiumPayingTermMonths,
                                    String premiumFrequency,
                                    // A scheme case, and what it asks for (V9).
                                    //
                                    // groupScheme IS serialized: the queue must be able to tell a
                                    // 500-life scheme from an individual proposal in a list, and
                                    // sumAssuredAmount cannot say which -- it is deliberately NULL
                                    // on a group case, because valuing a schedule needs
                                    // GroupBenefitCalculator and that lives in policy.
                                    // openapi-underwriting.yaml's schema grows to match, since it
                                    // declares additionalProperties: false.
                                    boolean groupScheme,
                                    // @JsonIgnore for the same reason beneficiaries below are: the
                                    // console reads the full proposal from the case DETAIL endpoint,
                                    // and smuggling a 500-row schedule onto every row of a
                                    // twenty-case queue page is not a list response.
                                    @JsonIgnore GroupProposal groupProposal,
                                    // What the product's rating table priced this applicant at (V8):
                                    // age band x sum assured band, as the engine resolved it.
                                    //
                                    // @JsonIgnore for the additionalProperties:false reason below --
                                    // and only for that reason. This is not an internal detail: it is
                                    // the answer to "why is this premium what it is", and the case
                                    // detail page is where an underwriter would ask. Exposing it means
                                    // an openapi-underwriting.yaml change and a console field, which is
                                    // worth doing and is not this change.
                                    @JsonIgnore BigDecimal ratingMultiplier,
                                    // Why automatic issuance failed on this decided case, and when
                                    // (V10). BOTH SERIALIZED, and openapi-underwriting.yaml's
                                    // schema grows to match -- it declares
                                    // additionalProperties: false.
                                    //
                                    // This is the one field here whose entire purpose is to be
                                    // read by a person on a screen. An acceptance that issued no
                                    // policy used to be indistinguishable from one that did, with
                                    // the difference recorded only in a log file; @JsonIgnore
                                    // would put it straight back there.
                                    String issuanceFailureReason,
                                    Instant issuanceFailedAt,
                                    // @JsonIgnore for the same reason sumAssuredAmount above is:
                                    // the issuance listener reads it in-process, and the response
                                    // schema declares additionalProperties:false. The console
                                    // reads nominations from the case detail endpoint instead,
                                    // where they can be a first-class list rather than a field
                                    // smuggled onto every case summary in a page of twenty.
                                    @JsonIgnore List<BeneficiaryNomination> beneficiaries,
                                    // Separation of duties: who opened the case and who wrote its
                                    // evidence, none of whom may decide it. Identity-provider
                                    // subjects, exactly as claims exposes its assessor -- things to
                                    // compare against the signed-in user, not to show a person.
                                    // Serialized, so the decision panel can say "another
                                    // underwriter must decide this" before a 403 has to.
                                    String openedBy,
                                    List<String> assessedBy,
                                    // A scheme member's evidence case (V11): whose free-cover-limit
                                    // excess it decides. Null on a proposal. Serialized, so the
                                    // queue and the case page can say what is being decided.
                                    String evidenceForPolicyNumber,
                                    UUID evidenceForMemberId,
                                    // IFRS 17 I2 (V18): the channel the sale came through and the
                                    // branch it belongs to -- what the policy takes at issue -- and
                                    // when the issue fixed them (null while they can still change).
                                    String salesChannel,
                                    String branchCode,
                                    Instant saleLockedAt) {

    /**
     * The sum assured the proposal asked for, as Money on the wire -- null on a group case, which
     * has none until policy values its schedule.
     *
     * <p>The two components above stay {@code @JsonIgnore}: they were hidden only to satisfy the
     * response schema, never for a business reason, and that left the console's issue form unable
     * to prefill the one figure every manual issue needs -- staff typed the sum assured again for
     * every policy issued from a case (found by the user, 2026-10-02). The schema now declares it.
     */
    @JsonProperty("sumAssured")
    public java.util.Map<String, String> sumAssuredOnTheWire() {
        if (sumAssuredAmount == null || sumAssuredCurrency == null) {
            return null;
        }
        return java.util.Map.of("amount", sumAssuredAmount.setScale(2, java.math.RoundingMode.HALF_EVEN).toPlainString(),
            "currencyCode", sumAssuredCurrency);
    }
}
