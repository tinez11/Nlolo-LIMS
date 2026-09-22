package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRow;
import tz.co.nlolo.lifeplatform.policy.api.RowOutcome;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of a lender's schedule and what happened to it.
 *
 * <p>Written at submission with its outcome already decided, and updated at acceptance
 * with the member it became. A row that is neither enrolled nor reported is the one
 * outcome this feature must not produce, so every line of the file gets one of these.
 */
@Entity
@Table(name = "enrolment_submission_row", schema = "policy")
public class EnrolmentSubmissionRow {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "submission_row_id")
    private UUID submissionRowId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    /** The line in the LENDER's file, so the report reads beside their own spreadsheet. */
    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "loan_account_number")
    private String loanAccountNumber;

    @Column(name = "borrower_full_name")
    private String borrowerFullName;

    /**
     * The loan AS JUDGED.
     *
     * <p>Carried here rather than re-read from the stored file at acceptance: re-parsing
     * would risk the schedule and the report disagreeing about what was approved, and it
     * is these values a second person is being asked to accept. Null on a row that
     * failed at parse, which by definition has no usable values.
     */
    @Column(name = "borrower_date_of_birth")
    private LocalDate borrowerDateOfBirth;

    @Column(name = "loan_principal_amount")
    private BigDecimal loanPrincipalAmount;

    @Column(name = "loan_term_months")
    private Integer loanTermMonths;

    @Column(name = "disbursement_date")
    private LocalDate disbursementDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private RowOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code")
    private EnrolmentRejection reasonCode;

    @Column(name = "reason")
    private String reason;

    @Column(name = "policy_member_id")
    private UUID policyMemberId;

    /** The reference we issued, so the report can tell the lender what to quote back. */
    @Column(name = "member_reference")
    private String memberReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected EnrolmentSubmissionRow() {}

    private EnrolmentSubmissionRow(UUID tenantId, UUID submissionId, int lineNumber,
                                    String loanAccountNumber, String borrowerFullName,
                                    RowOutcome outcome, EnrolmentRejection reasonCode,
                                    String reason) {
        this.tenantId = tenantId;
        this.submissionId = submissionId;
        this.lineNumber = lineNumber;
        this.loanAccountNumber = loanAccountNumber;
        this.borrowerFullName = borrowerFullName;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    /** Copy the judged loan onto a row that will become cover. */
    private EnrolmentSubmissionRow withLoan(EnrolmentRow source) {
        this.borrowerDateOfBirth = source.borrowerDateOfBirth();
        this.loanPrincipalAmount = source.loanPrincipalAmount();
        this.loanTermMonths = source.loanTermMonths();
        this.disbursementDate = source.disbursementDate();
        return this;
    }

    /** A row that will become cover when the submission is accepted. */
    public static EnrolmentSubmissionRow accepted(UUID tenantId, UUID submissionId,
                                                   EnrolmentRow source) {
        return new EnrolmentSubmissionRow(tenantId, submissionId, source.lineNumber(),
            source.loanAccountNumber(), source.borrowerFullName(),
            RowOutcome.ENROLLED, null, null).withLoan(source);
    }

    /**
     * Cover, capped at the free cover limit, with the excess referred. Not a refusal.
     *
     * <p>No reason CODE: the codes name rejections, and {@code ENROLLED_CAPPED} in the
     * outcome column already says what happened. The prose carries the limit, the excess
     * and the fact that a referral is open.
     */
    public static EnrolmentSubmissionRow capped(UUID tenantId, UUID submissionId,
                                                 EnrolmentRow source, String reason) {
        return new EnrolmentSubmissionRow(tenantId, submissionId, source.lineNumber(),
            source.loanAccountNumber(), source.borrowerFullName(),
            RowOutcome.ENROLLED_CAPPED, null, reason).withLoan(source);
    }

    /** Not covered, and the reason says so in those words. */
    public static EnrolmentSubmissionRow rejected(UUID tenantId, UUID submissionId, int lineNumber,
                                                   String loanAccountNumber, String borrowerFullName,
                                                   EnrolmentRejection reasonCode, String reason) {
        if (reasonCode == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                "A rejected row must carry a reason: it is the whole product of this feature");
        }
        return new EnrolmentSubmissionRow(tenantId, submissionId, lineNumber, loanAccountNumber,
            borrowerFullName, RowOutcome.REJECTED, reasonCode, reason);
    }

    /** Called at acceptance, once the row is genuinely a member. */
    public void becameMember(UUID policyMemberId, String memberReference) {
        this.policyMemberId = policyMemberId;
        this.memberReference = memberReference;
    }

    /** The loan this row was judged on, or null if it never parsed. */
    public LoanTermsSource getJudgedLoan() {
        if (loanPrincipalAmount == null) return null;
        return new LoanTermsSource(borrowerDateOfBirth, loanPrincipalAmount, loanTermMonths,
            disbursementDate);
    }

    /** The four judged values acceptance needs to build a {@code LoanTerms}. */
    public record LoanTermsSource(LocalDate borrowerDateOfBirth, BigDecimal loanPrincipalAmount,
                                   Integer loanTermMonths, LocalDate disbursementDate) {}

    public UUID getSubmissionRowId() { return submissionRowId; }
    public UUID getSubmissionId() { return submissionId; }
    public int getLineNumber() { return lineNumber; }
    public String getLoanAccountNumber() { return loanAccountNumber; }
    public String getBorrowerFullName() { return borrowerFullName; }
    public RowOutcome getOutcome() { return outcome; }
    public EnrolmentRejection getReasonCode() { return reasonCode; }
    public String getReason() { return reason; }
    public UUID getPolicyMemberId() { return policyMemberId; }
    public String getMemberReference() { return memberReference; }
}
