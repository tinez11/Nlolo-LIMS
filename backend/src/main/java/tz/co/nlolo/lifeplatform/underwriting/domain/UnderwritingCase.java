package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
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

    protected UnderwritingCase() {}

    public UnderwritingCase(UUID tenantId, UUID applicantPartyId, UUID productId, UUID productVersionId,
                             BigDecimal sumAssuredAmount, String sumAssuredCurrency, String createdBy) {
        this.tenantId = tenantId;
        this.applicantPartyId = applicantPartyId;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
        this.createdBy = createdBy;
    }

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
