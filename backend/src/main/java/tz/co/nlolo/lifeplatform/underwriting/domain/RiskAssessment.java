package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_assessment", schema = "underwriting")
public class RiskAssessment {

    @Id
    @UuidGenerator
    @Column(name = "risk_assessment_id")
    private UUID riskAssessmentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "assessment_type", nullable = false)
    private String assessmentType;

    @Column(nullable = false)
    private String assessor;

    @Column
    private String findings;

    @Column(name = "risk_score")
    private BigDecimal riskScore;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected RiskAssessment() {}

    public RiskAssessment(UUID tenantId, UUID caseId, String assessmentType, String assessor, String findings, BigDecimal riskScore) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.assessmentType = assessmentType;
        this.assessor = assessor;
        this.findings = findings;
        this.riskScore = riskScore;
    }

    public UUID getCaseId() { return caseId; }
    public String getAssessmentType() { return assessmentType; }
    public BigDecimal getRiskScore() { return riskScore; }
    public Instant getCreatedAt() { return createdAt; }
    /** The tie-breaker that makes "latest assessment" a total order — createdAt is
     *  Instant.now() in Java, so two assessments can share it exactly. */
    public UUID getRiskAssessmentId() { return riskAssessmentId; }
}
