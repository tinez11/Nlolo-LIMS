package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.policy.api.ExitReason;
import tz.co.nlolo.lifeplatform.policy.api.ExitRejection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of a lender's exits file and what happened to it.
 *
 * <p>The judged values are stored here rather than re-read from the file at acceptance.
 * Re-parsing would risk the roll and the report disagreeing about what was approved, and it is
 * these values a second person is being asked to accept.
 */
@Entity
@Table(name = "exit_submission_row", schema = "policy")
public class ExitSubmissionRow {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "submission_row_id")
    private UUID submissionRowId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "submission_id", nullable = false)
    private UUID submissionId;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "member_reference")
    private String memberReference;

    @Column(name = "exit_date")
    private LocalDate exitDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "exit_reason")
    private ExitReason exitReason;

    @Column(name = "outstanding_balance_at_exit")
    private BigDecimal outstandingBalanceAtExit;

    @Column(name = "outcome", nullable = false)
    private String outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code")
    private ExitRejection reasonCode;

    @Column(name = "reason")
    private String reason;

    /** Written at acceptance, so a report can still say which member this row took off cover. */
    @Column(name = "policy_member_id")
    private UUID policyMemberId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ExitSubmissionRow() {}

    private ExitSubmissionRow(UUID tenantId, UUID submissionId, int lineNumber,
                               String memberReference, String outcome,
                               ExitRejection reasonCode, String reason) {
        this.tenantId = tenantId;
        this.submissionId = submissionId;
        this.lineNumber = lineNumber;
        this.memberReference = memberReference;
        this.outcome = outcome;
        this.reasonCode = reasonCode;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    /** A row that will end cover once a second person accepts the file. */
    public static ExitSubmissionRow actionable(UUID tenantId, UUID submissionId, int lineNumber,
                                                String memberReference, LocalDate exitDate,
                                                ExitReason exitReason,
                                                BigDecimal outstandingBalanceAtExit) {
        ExitSubmissionRow row = new ExitSubmissionRow(tenantId, submissionId, lineNumber,
            memberReference, "EXITED", null, null);
        row.exitDate = exitDate;
        row.exitReason = exitReason;
        row.outstandingBalanceAtExit = outstandingBalanceAtExit;
        return row;
    }

    /**
     * A row that will not.
     *
     * <p>The reason is the entire product of this file, and it matters here for the opposite
     * reason it does on an enrolment report: a lender who believes a loan came off cover will
     * stop expecting to be charged for it, and will not chase a refund that never comes.
     */
    public static ExitSubmissionRow rejected(UUID tenantId, UUID submissionId, int lineNumber,
                                              String memberReference, ExitRejection reasonCode,
                                              String reason) {
        return new ExitSubmissionRow(tenantId, submissionId, lineNumber, memberReference,
            "REJECTED", reasonCode, reason);
    }

    public void becameExit(UUID policyMemberId) {
        this.policyMemberId = policyMemberId;
    }

    public boolean isRejected() { return "REJECTED".equals(outcome); }

    public UUID getSubmissionRowId() { return submissionRowId; }
    public UUID getSubmissionId() { return submissionId; }
    public int getLineNumber() { return lineNumber; }
    public String getMemberReference() { return memberReference; }
    public LocalDate getExitDate() { return exitDate; }
    public ExitReason getExitReason() { return exitReason; }
    public BigDecimal getOutstandingBalanceAtExit() { return outstandingBalanceAtExit; }
    public String getOutcome() { return outcome; }
    public ExitRejection getReasonCode() { return reasonCode; }
    public String getReason() { return reason; }
    public UUID getPolicyMemberId() { return policyMemberId; }
}
