package tz.co.nlolo.lifeplatform.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Maps {@code claims.claim_assessment} 1:1. No {@code @Version}: the table has no version
 * column and docs/06-database-schema.md:27 does not list it. Append-only in practice -- every
 * assessment is a new row, never mutated -- so no transition methods are needed here; the
 * lifecycle logic lives entirely on {@link Claim}. */
@Entity
@Table(name = "claim_assessment", schema = "claims")
public class ClaimAssessment {

    @Id
    @UuidGenerator
    @Column(name = "claim_assessment_id")
    private UUID claimAssessmentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(nullable = false)
    private String assessor;

    @Column
    private String findings;

    @Column(name = "recommended_amount")
    private BigDecimal recommendedAmount;

    @Column(name = "recommended_currency")
    private String recommendedCurrency;

    @Column(name = "fraud_indicator", nullable = false)
    private boolean fraudIndicator = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ClaimAssessment() {}

    public ClaimAssessment(UUID tenantId, UUID claimId, String assessor, String findings,
                            BigDecimal recommendedAmount, String recommendedCurrency, boolean fraudIndicator) {
        this.tenantId = tenantId;
        this.claimId = claimId;
        this.assessor = assessor;
        this.findings = findings;
        this.recommendedAmount = recommendedAmount;
        this.recommendedCurrency = recommendedCurrency;
        this.fraudIndicator = fraudIndicator;
    }

    public UUID getClaimAssessmentId() { return claimAssessmentId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getClaimId() { return claimId; }
    public String getAssessor() { return assessor; }
    public String getFindings() { return findings; }
    public BigDecimal getRecommendedAmount() { return recommendedAmount; }
    public String getRecommendedCurrency() { return recommendedCurrency; }
    public boolean isFraudIndicator() { return fraudIndicator; }
    public Instant getCreatedAt() { return createdAt; }
}
