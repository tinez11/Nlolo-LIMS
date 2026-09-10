package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One band on a proposed GRADED scheme: a staff category and what it is worth.
 *
 * <p>Meaningful only on a GRADED proposal. A grade table on a flat or salary-multiple scheme
 * is a proposer who believes something about the contract that is not true, which is why
 * {@code GroupBenefitCalculator} rejects a grade code on those bases rather than ignoring it.
 */
@Entity
@Table(name = "proposal_group_grade", schema = "underwriting")
public class ProposalGroupGrade {

    @Id
    @GeneratedValue
    @Column(name = "proposal_group_grade_id")
    private UUID proposalGroupGradeId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "grade_code", nullable = false)
    private String gradeCode;

    @Column(name = "benefit_amount", nullable = false)
    private BigDecimal benefitAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProposalGroupGrade() {}

    public ProposalGroupGrade(UUID tenantId, UUID caseId, String gradeCode, BigDecimal benefitAmount) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.gradeCode = gradeCode;
        this.benefitAmount = benefitAmount;
        this.createdAt = Instant.now();
    }

    public String getGradeCode() { return gradeCode; }
    public BigDecimal getBenefitAmount() { return benefitAmount; }
}
