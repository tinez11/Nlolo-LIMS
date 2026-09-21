package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.policy.api.MemberType;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One insured life on a master group policy.
 *
 * <p>Deliberately NOT a policy of its own: the confirmed model is one master policy with
 * many members attached. The member's money lives on effective-dated
 * {@link PolicyMemberBenefit} rows, because a claim must pay the benefit in force on the
 * date of event rather than whatever today's inputs would produce.
 */
@Entity
@Table(name = "policy_member", schema = "policy")
public class PolicyMember {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "policy_member_id")
    private UUID policyMemberId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    /** Null on a FREEFORM member -- the name below is the designation instead. */
    @Column(name = "member_party_id")
    private UUID memberPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "member_type", nullable = false)
    private MemberType memberType;

    @Column(name = "member_name")
    private String memberName;

    @Column(name = "member_date_of_birth")
    private LocalDate memberDateOfBirth;

    @Column(name = "grade_code")
    private String gradeCode;

    @Column(name = "joined_on", nullable = false)
    private LocalDate joinedOn;

    @Column(name = "left_on")
    private LocalDate leftOn;

    @Column(name = "status", nullable = false)
    private String status;

    @Enumerated(EnumType.STRING)
    @Column(name = "underwriting_status", nullable = false)
    private MemberUnderwritingStatus underwritingStatus;

    @Column(name = "underwriting_case_id")
    private UUID underwritingCaseId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private String createdBy;

    protected PolicyMember() {}

    /** A member who is a registered party. */
    public PolicyMember(UUID tenantId, String policyNumber, UUID memberPartyId, String gradeCode,
                         LocalDate joinedOn, MemberUnderwritingStatus underwritingStatus, String createdBy) {
        if (memberPartyId == null) {
            throw new IllegalArgumentException("A PARTY member must name a party");
        }
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.memberType = MemberType.PARTY;
        this.memberPartyId = memberPartyId;
        this.gradeCode = gradeCode;
        this.joinedOn = joinedOn;
        this.status = "ACTIVE";
        this.underwritingStatus = underwritingStatus;
        this.createdAt = Instant.now();
        this.createdBy = createdBy;
    }

    /**
     * A member named on a schedule who is not a registered party.
     *
     * <p>A static factory rather than a second constructor: the two differ only in which
     * designation they carry, and two constructors of the same arity would let a caller
     * pass a name where a party id belonged and never hear about it.
     */
    public static PolicyMember freeform(UUID tenantId, String policyNumber, String memberName,
                                         LocalDate memberDateOfBirth, String gradeCode,
                                         LocalDate joinedOn,
                                         MemberUnderwritingStatus underwritingStatus,
                                         String createdBy) {
        if (memberName == null || memberName.isBlank()) {
            throw new IllegalArgumentException("A FREEFORM member must have a name");
        }
        PolicyMember member = new PolicyMember();
        member.tenantId = tenantId;
        member.policyNumber = policyNumber;
        member.memberType = MemberType.FREEFORM;
        member.memberName = memberName.trim();
        member.memberDateOfBirth = memberDateOfBirth;
        member.gradeCode = gradeCode;
        member.joinedOn = joinedOn;
        member.status = "ACTIVE";
        member.underwritingStatus = underwritingStatus;
        member.createdAt = Instant.now();
        member.createdBy = createdBy;
        return member;
    }

    /**
     * Record that this member has left the scheme.
     *
     * <p>The row is kept rather than deleted: a claim can arrive after someone leaves, and
     * "were they covered on the date of event" needs the dates to still exist.
     */
    public void exit(LocalDate leftOn) {
        if (leftOn == null || leftOn.isBefore(joinedOn)) {
            throw new IllegalArgumentException("A member cannot leave before they joined");
        }
        this.status = "EXITED";
        this.leftOn = leftOn;
    }

    /** Link the underwriting case opened because this benefit exceeds the free cover limit. */
    public void referForEvidence(UUID underwritingCaseId) {
        this.underwritingCaseId = underwritingCaseId;
        this.underwritingStatus = MemberUnderwritingStatus.EVIDENCE_REQUIRED;
    }

    /**
     * Record the outcome of the excess underwriting.
     *
     * <p>DECLINED does not mean uninsured -- the member keeps the free cover limit. The
     * resulting covered amount is written on a new benefit row by the caller.
     */
    public void recordEvidenceDecision(boolean accepted) {
        this.underwritingStatus = accepted
            ? MemberUnderwritingStatus.ACCEPTED
            : MemberUnderwritingStatus.DECLINED;
    }

    public UUID getPolicyMemberId() { return policyMemberId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getMemberPartyId() { return memberPartyId; }
    public MemberType getMemberType() { return memberType; }
    public String getMemberName() { return memberName; }
    public LocalDate getMemberDateOfBirth() { return memberDateOfBirth; }
    public String getGradeCode() { return gradeCode; }
    public LocalDate getJoinedOn() { return joinedOn; }
    public LocalDate getLeftOn() { return leftOn; }
    public String getStatus() { return status; }
    public MemberUnderwritingStatus getUnderwritingStatus() { return underwritingStatus; }
    public UUID getUnderwritingCaseId() { return underwritingCaseId; }
}
