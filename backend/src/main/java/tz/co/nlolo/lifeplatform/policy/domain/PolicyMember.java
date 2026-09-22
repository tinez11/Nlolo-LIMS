package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.policy.api.LoanTerms;
import tz.co.nlolo.lifeplatform.policy.api.MemberType;
import tz.co.nlolo.lifeplatform.policy.api.MemberUnderwritingStatus;
import tz.co.nlolo.lifeplatform.policy.api.RepaymentFrequency;

import java.math.BigDecimal;
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

    // ---- The loan this member's cover is measured against. -------------------
    // All null on an ordinary group member, all present together on a credit-life one --
    // chk_policy_member_loan_complete is the guarantee. A member with a principal but no
    // term would produce a schedule the application has to guess at.

    /**
     * What the LENDER quotes back at us. Issued by the insurer, because the lender has
     * none of their own to give: one policy number goes to the bank and sheets come back,
     * with nothing on them distinguishing one borrower from another.
     *
     * <p>Null on an ordinary group member; required on any member carrying a loan, which
     * {@code chk_policy_member_loan_has_reference} enforces.
     */
    @Column(name = "member_reference")
    private String memberReference;

    /**
     * The lender's own identifier for the loan, when they have one. Optional and, on both
     * real lenders' files today, absent -- which is why it is not the key.
     */
    @Column(name = "loan_account_number")
    private String loanAccountNumber;

    @Column(name = "loan_principal_amount")
    private BigDecimal loanPrincipalAmount;

    @Column(name = "loan_annual_rate_percent")
    private BigDecimal loanAnnualRatePercent;

    @Column(name = "loan_term_months")
    private Integer loanTermMonths;

    @Column(name = "loan_repayment_frequency")
    private String loanRepaymentFrequency;

    @Column(name = "loan_disbursement_date")
    private LocalDate loanDisbursementDate;

    @Column(name = "loan_first_repayment_date")
    private LocalDate loanFirstRepaymentDate;

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

    /**
     * Give a freeform life a real identity.
     *
     * <p>Only for a member who has to be underwritten. Freeform exists to keep the KYC
     * queue clear of people nobody needs to identify; somebody over the free cover limit
     * is precisely somebody you do, and an underwriting case cannot be opened without a
     * party to open it against.
     *
     * <p>The name is cleared because the party record now holds it. Keeping both would
     * be two copies of one fact, free to disagree --
     * {@code chk_policy_member_exactly_one_designation} refuses the row anyway.
     */
    public void promoteToParty(UUID partyId) {
        if (partyId == null) {
            throw new IllegalArgumentException("Promoting a member needs the party to promote them to");
        }
        if (memberType == MemberType.PARTY) {
            throw new IllegalStateException("This member already names a registered party");
        }
        this.memberType = MemberType.PARTY;
        this.memberPartyId = partyId;
        this.memberName = null;
        this.memberDateOfBirth = null;
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
    /**
     * Attach the loan this member's cover is measured against. Returns this, so it
     * chains off whichever designation factory built the member.
     */
    public PolicyMember withLoan(String memberReference, String loanAccountNumber, LoanTerms terms) {
        if (memberReference == null || memberReference.isBlank()) {
            throw new IllegalArgumentException(
                "A credit-life member needs a member reference; it is how the lender names "
                    + "them afterwards, and they have no identifier of their own to give");
        }
        if (terms == null) {
            throw new IllegalArgumentException("A credit-life member needs the terms of their loan");
        }
        this.memberReference = memberReference.trim();
        // Optional: kept when a lender does send one, never relied on.
        this.loanAccountNumber = loanAccountNumber == null || loanAccountNumber.isBlank()
            ? null : loanAccountNumber.trim();
        this.loanPrincipalAmount = terms.principalAmount();
        this.loanAnnualRatePercent = terms.annualInterestRatePercent();
        this.loanTermMonths = terms.termMonths();
        this.loanRepaymentFrequency = terms.repaymentFrequency().name();
        this.loanDisbursementDate = terms.disbursementDate();
        this.loanFirstRepaymentDate = terms.firstRepaymentDate();
        return this;
    }

    /**
     * This member's loan, or null on an ordinary group member.
     *
     * <p>Rebuilt from the columns rather than stored as an object, so the record's own
     * invariants are re-asserted on every read: a row that somehow lost its term would
     * fail here rather than quietly produce a schedule.
     */
    public LoanTerms getLoanTerms() {
        if (loanAccountNumber == null) return null;
        return new LoanTerms(loanPrincipalAmount, loanAnnualRatePercent, loanTermMonths,
            RepaymentFrequency.valueOf(loanRepaymentFrequency),
            loanDisbursementDate, loanFirstRepaymentDate);
    }

    public String getLoanAccountNumber() { return loanAccountNumber; }
    public String getMemberReference() { return memberReference; }
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
