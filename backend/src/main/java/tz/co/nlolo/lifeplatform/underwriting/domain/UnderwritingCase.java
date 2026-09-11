package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import tz.co.nlolo.lifeplatform.underwriting.api.ProposalDetails;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "underwriting_case", schema = "underwriting")
public class UnderwritingCase {

    @Id
    @UuidGenerator
    @Column(name = "case_id")
    private UUID caseId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "applicant_party_id", nullable = false)
    private UUID applicantPartyId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "referral_status", nullable = false)
    private String referralStatus = "NONE";

    @Column(name = "decision_outcome")
    private String decisionOutcome;

    @Column(name = "decision_loading_percent")
    private BigDecimal decisionLoadingPercent;

    @Column(name = "decision_decline_reason")
    private String decisionDeclineReason;

    @Column(name = "decision_decided_at")
    private Instant decisionDecidedAt;

    @Column(name = "sum_assured_amount")
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency")
    private String sumAssuredCurrency;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    /**
     * Who sold it. An opaque ref into {@code distribution.agent_profile}, carried so the
     * automatic issuance path can name an agent of record — without it, every automatically
     * issued policy was direct-sold and no commission ever accrued on the normal path.
     * Null is a real state: a self-service application has no agent.
     */
    @Column(name = "agent_of_record_id")
    private UUID agentOfRecordId;

    // Proposal identity (V4). proposalNumber is the human handle a case never had --
    // a bare UUID cannot be quoted over the phone. lifeAssuredPartyId is the
    // consequential one: until it existed, a proposal where the policyholder insures
    // somebody else could not be expressed at all.
    @Column(name = "proposal_number")
    private String proposalNumber;

    @Column(name = "life_assured_party_id")
    private UUID lifeAssuredPartyId;

    @Column(name = "branch")
    private String branch;

    @Column(name = "source_of_business")
    private String sourceOfBusiness;

    @Column(name = "proposed_commencement_date")
    private LocalDate proposedCommencementDate;

    // What the applicant asked for about the CONTRACT (V6), as distinct from the risk above.
    // All optional: a product that does not term has no term, and a proposal with the
    // nomination blank is routine. Until these existed, only the manual issue form collected
    // them -- so a policy issued on the normal path had no term, no maturity date, and nobody
    // nominated, because nobody had ever asked.
    @Column(name = "requested_term_months")
    private Integer requestedTermMonths;

    @Column(name = "premium_paying_term_months")
    private Integer premiumPayingTermMonths;

    @Column(name = "premium_frequency")
    private String premiumFrequency;

    // The engine's advice (V5), kept apart from the decision columns above. Recomputed on every
    // assessment, and authoritative for nothing -- it exists so an underwriter deciding a case
    // is not starting from a blank page, and so a decision that departs from it is visible as a
    // departure rather than as an unexplained outcome.
    @Column(name = "recommendation_outcome")
    private String recommendationOutcome;

    @Column(name = "recommendation_loading_percent")
    private BigDecimal recommendationLoadingPercent;

    @Column(name = "recommendation_reason")
    private String recommendationReason;

    @Column(name = "recommendation_at")
    private Instant recommendationAt;

    /**
     * What the product's rating table priced this applicant at (V8): age band x sum assured band.
     *
     * <p>Recomputed alongside the recommendation, but NOT advisory -- unlike the columns above it,
     * this one reaches a contract. Issuance multiplies the premium by it, which is the whole reason
     * it is persisted: the multiplier used to exist only as a local variable inside the engine, so
     * the rating table moved decisions and never moved a price.
     *
     * <p>NULL on a case last assessed before V8. Issuance treats that as neutral, because a neutral
     * multiplier is exactly what those policies were priced at.
     */
    @Column(name = "rating_multiplier")
    private BigDecimal ratingMultiplier;

    /**
     * Why automatic issuance failed on this decided case, and when (V10).
     *
     * <p>Issuance runs in an AFTER_COMMIT listener, so a failure there cannot roll the decision
     * back — the case is decided whatever happens next. Until this column, that meant an ACCEPT
     * whose policy was never created looked exactly like an ACCEPT whose policy was, and the only
     * record of the difference was a stack trace in a log file. One sat undetected until somebody
     * happened to ask why a customer had no policy.
     */
    @Column(name = "issuance_failure_reason")
    private String issuanceFailureReason;

    @Column(name = "issuance_failed_at")
    private Instant issuanceFailedAt;

    /** NULL for a pre-V5 case: those were decided by the engine, and no person authored them. */
    @Column(name = "decision_decided_by")
    private String decisionDecidedBy;

    @Column(name = "decision_overrode_recommendation")
    private boolean decisionOverrodeRecommendation;

    protected UnderwritingCase() {}

    public UnderwritingCase(UUID tenantId, UUID applicantPartyId, UUID productId, UUID productVersionId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency, String createdBy) {
        this(tenantId, applicantPartyId, productId, productVersionId, sumAssuredAmount, sumAssuredCurrency,
            null, createdBy);
    }

    public UnderwritingCase(UUID tenantId, UUID applicantPartyId, UUID productId, UUID productVersionId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency, UUID agentOfRecordId,
                             String createdBy) {
        this.tenantId = tenantId;
        this.applicantPartyId = applicantPartyId;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.agentOfRecordId = agentOfRecordId;
        this.createdBy = createdBy;
    }

    public UUID getAgentOfRecordId() { return agentOfRecordId; }

    /**
     * Record the proposal's identity and who it insures.
     *
     * <p>{@code lifeAssuredPartyId} arrives already resolved — the service turns a null
     * (self-insured) into the applicant before calling this, so the column is always
     * answerable rather than carrying a null that every reader has to interpret.
     */
    public void recordProposal(String proposalNumber, UUID lifeAssuredPartyId, ProposalDetails details) {
        this.proposalNumber = proposalNumber;
        this.lifeAssuredPartyId = lifeAssuredPartyId;
        this.branch = details.branch();
        this.sourceOfBusiness = details.sourceOfBusiness();
        this.proposedCommencementDate = details.proposedCommencementDate();
        this.requestedTermMonths = details.requestedTermMonths();
        this.premiumPayingTermMonths = details.premiumPayingTermMonths();
        this.premiumFrequency = details.premiumFrequency();
    }

    /**
     * A group scheme's proposal identity. No life assured, deliberately.
     *
     * <p>Separate from {@link #recordProposal} because that one takes an already-resolved,
     * NON-NULL life assured — correct for individual business, where a null input means "the
     * applicant insures themselves" and the service resolves it before calling, "so the column
     * is always answerable rather than carrying a null that every reader has to interpret".
     *
     * <p>A scheme has no single life to resolve to, and resolving to the applicant would record
     * that the EMPLOYER is insured. {@code policy/V8} had to migrate 28 rows to undo exactly
     * that assertion on the policy side; this is the same rule applied before the mistake can
     * be made. So {@code life_assured_party_id} stays NULL on a group case, meaning "not a
     * single person" — the lives are the proposed schedule.
     *
     * <p>{@code premiumFrequency} is set too: it is a term of the scheme, it is on the
     * proposal, and billing reads it off the issued policy either way.
     */
    public void recordGroupProposal(String proposalNumber, LocalDate proposedCommencementDate,
                                     String premiumFrequency) {
        this.proposalNumber = proposalNumber;
        this.proposedCommencementDate = proposedCommencementDate;
        this.premiumFrequency = premiumFrequency;
    }

    public Integer getRequestedTermMonths() { return requestedTermMonths; }
    public Integer getPremiumPayingTermMonths() { return premiumPayingTermMonths; }
    public String getPremiumFrequency() { return premiumFrequency; }

    public String getProposalNumber() { return proposalNumber; }
    public UUID getLifeAssuredPartyId() { return lifeAssuredPartyId; }
    public String getBranch() { return branch; }
    public String getSourceOfBusiness() { return sourceOfBusiness; }
    public LocalDate getProposedCommencementDate() { return proposedCommencementDate; }

    public UUID getCaseId() { return caseId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getApplicantPartyId() { return applicantPartyId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public String getStatus() { return status; }
    public String getReferralStatus() { return referralStatus; }
    public String getDecisionOutcome() { return decisionOutcome; }
    public BigDecimal getDecisionLoadingPercent() { return decisionLoadingPercent; }
    public String getDecisionDeclineReason() { return decisionDeclineReason; }
    public Instant getDecisionDecidedAt() { return decisionDecidedAt; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }

    /**
     * The rules engine's opinion. Advisory: it never moves the case to DECIDED, and it is
     * recomputed from scratch every time fresh evidence arrives.
     *
     * <p>Separate from {@link #recordDecision} because they were the same thing until now, and
     * that was the defect. {@code SimpleRulesEngine} -- whose own comment calls its thresholds
     * "illustrative, not actuarially validated" -- wrote directly into the decision columns,
     * and an ACCEPT there published UnderwritingDecisionMade and issued a real policy with no
     * person anywhere in the chain.
     */
    public void recordRecommendation(String outcome, BigDecimal loadingPercent, String reason,
                                      BigDecimal ratingMultiplier) {
        this.recommendationOutcome = outcome;
        this.recommendationLoadingPercent = loadingPercent;
        this.recommendationReason = reason;
        this.recommendationAt = Instant.now();
        // Written on the same call and never separately: the multiplier is the basis the rest of
        // this recommendation was reached on, and a case carrying one from an earlier evaluation
        // and a recommendation from a later one would be an explanation that does not match its
        // own conclusion.
        this.ratingMultiplier = ratingMultiplier;
    }

    /**
     * A person's decision. The only thing that moves a case to DECIDED, and the only thing
     * downstream issuance reacts to.
     *
     * @param overrodeRecommendation whether this departed from {@link #getRecommendationOutcome()}.
     *     Stored rather than derived, because the recommendation is recomputed by any later
     *     assessment and the fact that a human once disagreed must not be rewritten by it.
     */
    public void recordDecision(String outcome, BigDecimal loadingPercent, String declineReason,
                                String decidedBy, boolean overrodeRecommendation) {
        this.decisionOutcome = outcome;
        this.decisionLoadingPercent = loadingPercent;
        this.decisionDeclineReason = declineReason;
        this.decisionDecidedAt = Instant.now();
        this.decisionDecidedBy = decidedBy;
        this.decisionOverrodeRecommendation = overrodeRecommendation;
        this.status = "DECIDED";
    }

    /**
     * Automatic issuance failed, or succeeded after having failed.
     *
     * <p>Set and cleared through one method so the reason and the timestamp can never disagree —
     * {@code underwriting_case_issuance_failure_shape} says the same thing at the database.
     *
     * <p>CLEARING MATTERS AS MUCH AS SETTING. A case that failed, was corrected and then issued
     * must stop claiming it failed. A warning that stays up after it has been dealt with is one
     * people learn to scroll past, which is how the log entry this replaces came to be ignored.
     */
    public void recordIssuanceFailure(String reason) {
        this.issuanceFailureReason = reason;
        this.issuanceFailedAt = reason != null ? Instant.now() : null;
    }

    public String getIssuanceFailureReason() { return issuanceFailureReason; }
    public Instant getIssuanceFailedAt() { return issuanceFailedAt; }

    public String getRecommendationOutcome() { return recommendationOutcome; }
    public BigDecimal getRecommendationLoadingPercent() { return recommendationLoadingPercent; }
    public String getRecommendationReason() { return recommendationReason; }
    public Instant getRecommendationAt() { return recommendationAt; }
    public BigDecimal getRatingMultiplier() { return ratingMultiplier; }
    public String getDecisionDecidedBy() { return decisionDecidedBy; }
    public boolean isDecisionOverrodeRecommendation() { return decisionOverrodeRecommendation; }

    public void markInReview() {
        this.status = "IN_REVIEW";
    }

    public void referToSeniorUnderwriter() {
        // U1: referralStatus tracked separately from decision.outcome -- a borderline
        // case under senior review doesn't need a fake interim outcome value.
        this.referralStatus = "REFERRED_TO_SENIOR";
    }
}
