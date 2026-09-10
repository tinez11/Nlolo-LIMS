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
 * One life on a proposed opening schedule.
 *
 * <p>No {@code joinedOn}, unlike {@code policy.policy_member}: every life on an OPENING
 * schedule joins when the scheme commences, which the proposal's own commencement date already
 * says. A per-line date here would be a second answer to a settled question. Members who join
 * after issuance are admitted against the scheme, not against the proposal.
 *
 * <p>No benefit or covered amount either. What a member is worth follows from the scheme's
 * basis and is computed by {@code GroupBenefitCalculator} at issuance — which lives in
 * {@code policy} and which underwriting must not re-implement, because two copies of money
 * arithmetic are two copies that can drift.
 */
@Entity
@Table(name = "proposal_group_member", schema = "underwriting")
public class ProposalGroupMember {

    @Id
    @GeneratedValue
    @Column(name = "proposal_group_member_id")
    private UUID proposalGroupMemberId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "member_party_id", nullable = false)
    private UUID memberPartyId;

    @Column(name = "grade_code")
    private String gradeCode;

    @Column(name = "salary_amount")
    private BigDecimal salaryAmount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProposalGroupMember() {}

    public ProposalGroupMember(UUID tenantId, UUID caseId, UUID memberPartyId,
                                String gradeCode, BigDecimal salaryAmount) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.memberPartyId = memberPartyId;
        this.gradeCode = gradeCode;
        this.salaryAmount = salaryAmount;
        this.createdAt = Instant.now();
    }

    public UUID getProposalGroupMemberId() { return proposalGroupMemberId; }
    public UUID getMemberPartyId() { return memberPartyId; }
    public String getGradeCode() { return gradeCode; }
    public BigDecimal getSalaryAmount() { return salaryAmount; }
}
