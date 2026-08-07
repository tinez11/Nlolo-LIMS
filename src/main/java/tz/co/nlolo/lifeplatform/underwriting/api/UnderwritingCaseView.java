package tz.co.nlolo.lifeplatform.underwriting.api;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.Instant;
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
                                    @JsonIgnore BigDecimal sumAssuredAmount, @JsonIgnore String sumAssuredCurrency) {}
