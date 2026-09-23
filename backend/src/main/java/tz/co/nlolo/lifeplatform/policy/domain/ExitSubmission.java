package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.policy.api.SubmissionStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * A lender's exits file, submitted and awaiting a second pair of eyes.
 *
 * <p>Creating one of these takes NOBODY off cover. Every row has been read and judged and its
 * outcome recorded, but every loan stays insured until {@link #accept} — and that must be a
 * different person, for the same reason the enrolment file requires one.
 *
 * <p>If anything the control matters more on this side. A wrongly accepted enrolment file
 * insures somebody who should not have been; a wrongly accepted exits file UNINSURES somebody
 * who should have been, and nobody finds out until a claim is refused.
 */
@Entity
@Table(name = "exit_submission", schema = "policy")
public class ExitSubmission {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    /** The lender's own file, byte for byte, in the document store. */
    @Column(name = "document_ref", nullable = false)
    private String documentRef;

    @Column(name = "file_name")
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SubmissionStatus status;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "exited_count", nullable = false)
    private int exitedCount;

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

    protected ExitSubmission() {}

    public ExitSubmission(UUID tenantId, String policyNumber, String documentRef,
                           String fileName, int rowCount, int rejectedCount, String submittedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.documentRef = documentRef;
        this.fileName = fileName;
        this.status = SubmissionStatus.PENDING;
        this.rowCount = rowCount;
        this.exitedCount = 0;
        this.rejectedCount = rejectedCount;
        this.submittedBy = submittedBy;
        this.submittedAt = Instant.now();
    }

    /** Written once the stored file has an id, which it only has after the row exists. */
    public void recordDocument(String documentRef) {
        this.documentRef = documentRef;
    }

    /** The tallies, once every row has been judged. */
    public void recordJudgement(int rowCount, int rejectedCount) {
        this.rowCount = rowCount;
        this.rejectedCount = rejectedCount;
    }

    /**
     * Checked BEFORE any loan is taken off cover, so a refused acceptance leaves the roll
     * exactly as it was.
     */
    public void requireAcceptableBy(String acceptedBy) {
        if (status != SubmissionStatus.PENDING) {
            throw new IllegalStateException("This exits file is already "
                + status.name().toLowerCase() + " and cannot be accepted again");
        }
        if (acceptedBy == null || acceptedBy.isBlank()) {
            throw new IllegalArgumentException("Accepting an exits file needs the accepter's identity");
        }
        if (acceptedBy.equals(submittedBy)) {
            throw new IllegalStateException(acceptedBy + " cannot accept an exits file they"
                + " uploaded themselves; a second person must review it");
        }
    }

    public void accept(String acceptedBy, int exitedCount) {
        requireAcceptableBy(acceptedBy);
        this.status = SubmissionStatus.ACCEPTED;
        this.acceptedBy = acceptedBy;
        this.acceptedAt = Instant.now();
        this.exitedCount = exitedCount;
    }

    public void withdraw() {
        if (status != SubmissionStatus.PENDING) {
            throw new IllegalStateException("This exits file is already " + status.name().toLowerCase()
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
    public int getExitedCount() { return exitedCount; }
    public int getRejectedCount() { return rejectedCount; }
    public String getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getAcceptedBy() { return acceptedBy; }
    public Instant getAcceptedAt() { return acceptedAt; }
}
