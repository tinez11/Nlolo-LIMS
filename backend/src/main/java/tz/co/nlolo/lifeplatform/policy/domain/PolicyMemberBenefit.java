package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What a member was worth, and what they were actually covered for, from a given date.
 *
 * <p><b>Effective-dated, and that is the load-bearing decision of the group design.</b> A
 * claim pays the benefit in force on the date of event; a salary-multiple benefit
 * recomputed at claim time would value a two-year-old death at a salary the member did
 * not have when they died. So a salary change writes a NEW row rather than editing this
 * one, which is also what makes "benefit changes at renewal or by endorsement, never
 * silently" enforceable rather than merely a convention.
 *
 * <p>{@code benefitAmount} and {@code coveredAmount} differ whenever the free cover limit
 * bites: a 150m benefit against a 100m limit is 100m of real cover while evidence is
 * outstanding. Both are facts, and neither is derivable from the other once a decision
 * has been made -- ACCEPTED and DECLINED produce different covered amounts from identical
 * inputs.
 */
@Entity
@Table(name = "policy_member_benefit", schema = "policy")
public class PolicyMemberBenefit {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "policy_member_benefit_id")
    private UUID policyMemberBenefitId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_member_id", nullable = false)
    private UUID policyMemberId;

    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Present only on a SALARY_MULTIPLE scheme: the input the benefit came from. */
    @Column(name = "salary_amount")
    private BigDecimal salaryAmount;

    @Column(name = "benefit_amount", nullable = false)
    private BigDecimal benefitAmount;

    @Column(name = "covered_amount", nullable = false)
    private BigDecimal coveredAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    protected PolicyMemberBenefit() {}

    public PolicyMemberBenefit(UUID tenantId, UUID policyMemberId, LocalDate effectiveFrom,
                                BigDecimal salaryAmount, BigDecimal benefitAmount,
                                BigDecimal coveredAmount, String createdBy) {
        // Mirrors policy_member_benefit_covered_within_benefit. Cover above the benefit
        // would mean the scheme insures more than it values the member at.
        if (coveredAmount == null || benefitAmount == null) {
            throw new IllegalArgumentException("A benefit row needs both a benefit and a covered amount");
        }
        if (coveredAmount.compareTo(benefitAmount) > 0) {
            throw new IllegalArgumentException("Covered amount cannot exceed the benefit");
        }
        this.tenantId = tenantId;
        this.policyMemberId = policyMemberId;
        this.effectiveFrom = effectiveFrom;
        this.salaryAmount = salaryAmount;
        this.benefitAmount = benefitAmount;
        this.coveredAmount = coveredAmount;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }

    public UUID getPolicyMemberBenefitId() { return policyMemberBenefitId; }
    public UUID getPolicyMemberId() { return policyMemberId; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public BigDecimal getSalaryAmount() { return salaryAmount; }
    public BigDecimal getBenefitAmount() { return benefitAmount; }
    public BigDecimal getCoveredAmount() { return coveredAmount; }
}
