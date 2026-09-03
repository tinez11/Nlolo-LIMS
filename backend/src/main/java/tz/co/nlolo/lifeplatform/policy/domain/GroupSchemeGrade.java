package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One grade's benefit on a GRADED scheme, e.g. MANAGEMENT -> 20,000,000. */
@Entity
@Table(name = "group_scheme_grade", schema = "policy")
public class GroupSchemeGrade {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "group_scheme_grade_id")
    private UUID groupSchemeGradeId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "grade_code", nullable = false)
    private String gradeCode;

    @Column(name = "benefit_amount", nullable = false)
    private BigDecimal benefitAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GroupSchemeGrade() {}

    public GroupSchemeGrade(UUID tenantId, String policyNumber, String gradeCode, BigDecimal benefitAmount) {
        if (gradeCode == null || gradeCode.isBlank()) {
            throw new IllegalArgumentException("A grade needs a code");
        }
        if (benefitAmount == null || benefitAmount.signum() <= 0) {
            throw new IllegalArgumentException("Grade " + gradeCode + " needs a positive benefit amount");
        }
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.gradeCode = gradeCode;
        this.benefitAmount = benefitAmount;
        this.createdAt = Instant.now();
    }

    public UUID getGroupSchemeGradeId() { return groupSchemeGradeId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getGradeCode() { return gradeCode; }
    public BigDecimal getBenefitAmount() { return benefitAmount; }
}
