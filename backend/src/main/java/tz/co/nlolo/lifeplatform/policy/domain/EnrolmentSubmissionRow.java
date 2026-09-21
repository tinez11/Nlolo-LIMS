package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.policy.api.EnrolmentRejection;
import tz.co.nlolo.lifeplatform.policy.api.RowOutcome;

import java.time.Instant;
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

    /** A row that will become cover when the submission is accepted. */
    public static EnrolmentSubmissionRow accepted(UUID tenantId, UUID submissionId, int lineNumber,
                                                   String loanAccountNumber, String borrowerFullName) {
        return new EnrolmentSubmissionRow(tenantId, submissionId, lineNumber, loanAccountNumber,
            borrowerFullName, RowOutcome.ENROLLED, null, null);
    }

    /**
     * Cover, capped at the free cover limit, with the excess referred. Not a refusal.
     *
     * <p>No reason CODE: the codes name rejections, and {@code ENROLLED_CAPPED} in the
     * outcome column already says what happened. The prose carries the limit, the excess
     * and the fact that a referral is open.
     */
    public static EnrolmentSubmissionRow capped(UUID tenantId, UUID submissionId, int lineNumber,
                                                 String loanAccountNumber, String borrowerFullName,
                                                 String reason) {
        return new EnrolmentSubmissionRow(tenantId, submissionId, lineNumber, loanAccountNumber,
            borrowerFullName, RowOutcome.ENROLLED_CAPPED, null, reason);
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
    public void becameMember(UUID policyMemberId) {
        this.policyMemberId = policyMemberId;
    }

    public UUID getSubmissionRowId() { return submissionRowId; }
    public UUID getSubmissionId() { return submissionId; }
    public int getLineNumber() { return lineNumber; }
    public String getLoanAccountNumber() { return loanAccountNumber; }
    public String getBorrowerFullName() { return borrowerFullName; }
    public RowOutcome getOutcome() { return outcome; }
    public EnrolmentRejection getReasonCode() { return reasonCode; }
    public String getReason() { return reason; }
    public UUID getPolicyMemberId() { return policyMemberId; }
}
