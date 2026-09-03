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
    }

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

    public void recordDecision(String outcome, BigDecimal loadingPercent, String declineReason) {
        this.decisionOutcome = outcome;
        this.decisionLoadingPercent = loadingPercent;
        this.decisionDeclineReason = declineReason;
        this.decisionDecidedAt = Instant.now();
        this.status = "DECIDED";
    }

    public void markInReview() {
        this.status = "IN_REVIEW";
    }

    public void referToSeniorUnderwriter() {
        // U1: referralStatus tracked separately from decision.outcome -- a borderline
        // case under senior review doesn't need a fake interim outcome value.
        this.referralStatus = "REFERRED_TO_SENIOR";
    }
}
