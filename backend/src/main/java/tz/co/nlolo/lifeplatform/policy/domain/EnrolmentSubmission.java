package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.policy.api.SubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A lender's schedule, submitted and awaiting a second pair of eyes.
 *
 * <p>Creating one of these enrols nobody. Every row has been read and judged and its
 * outcome recorded, but no cover exists until {@link #accept(String)} -- and that must be
 * a different person, because a single user who can upload a file and then accept it has
 * an audit trail and no control.
 */
@Entity
@Table(name = "enrolment_submission", schema = "policy")
public class EnrolmentSubmission {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "document_ref", nullable = false)
    private String documentRef;

    @Column(name = "file_name")
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubmissionStatus status;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "enrolled_count", nullable = false)
    private int enrolledCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    @Column(name = "submitted_by", nullable = false)
    private String submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Column(name = "accepted_by")
    private String acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    protected EnrolmentSubmission() {}

    public EnrolmentSubmission(UUID tenantId, String policyNumber, String documentRef,
                                String fileName, int rowCount, int rejectedCount,
                                String submittedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.documentRef = documentRef;
        this.fileName = fileName;
        this.status = SubmissionStatus.PENDING;
        this.rowCount = rowCount;
        this.enrolledCount = 0;
        this.rejectedCount = rejectedCount;
        this.submittedBy = submittedBy;
        this.submittedAt = Instant.now();
    }

    /**
     * A second person turns this into cover.
     *
     * @throws IllegalStateException if it is not PENDING, or if the accepter is the
     *     person who uploaded it. {@code chk_enrolment_submission_two_person} is the
     *     guarantee; this is the readable error in front of it.
     */
    public void accept(String acceptedBy, int enrolledCount) {
        if (status != SubmissionStatus.PENDING) {
            throw new IllegalStateException("This submission is already " + status.name().toLowerCase()
                + " and cannot be accepted again");
        }
        if (acceptedBy == null || acceptedBy.isBlank()) {
            throw new IllegalArgumentException("Accepting a submission needs the accepter's identity");
        }
        if (acceptedBy.equals(submittedBy)) {
            throw new IllegalStateException(acceptedBy
                + " cannot accept a submission they uploaded themselves; a second person must review it");
        }
        this.status = SubmissionStatus.ACCEPTED;
        this.acceptedBy = acceptedBy;
        this.acceptedAt = Instant.now();
        this.enrolledCount = enrolledCount;
    }

    /**
     * Abandon it without enrolling anybody.
     *
     * <p>Allowed by the person who submitted it, because withdrawing puts nobody on risk
     * -- the two-person rule exists to stop cover being created unattended, not to stop
     * it being declined.
     */
    public void withdraw() {
        if (status != SubmissionStatus.PENDING) {
            throw new IllegalStateException("This submission is already " + status.name().toLowerCase()
                + " and cannot be withdrawn");
        }
        this.status = SubmissionStatus.WITHDRAWN;
    }

    public UUID getSubmissionId() { return submissionId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getDocumentRef() { return documentRef; }
    public String getFileName() { return fileName; }
    public SubmissionStatus getStatus() { return status; }
    public int getRowCount() { return rowCount; }
    public int getEnrolledCount() { return enrolledCount; }
    public int getRejectedCount() { return rejectedCount; }
    public String getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getAcceptedBy() { return acceptedBy; }
    public Instant getAcceptedAt() { return acceptedAt; }
}
