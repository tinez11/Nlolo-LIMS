package tz.co.nlolo.lifeplatform.benefitpayout.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.benefitpayout.api.InstalmentStatus;
import tz.co.nlolo.lifeplatform.benefitpayout.api.PayoutStateException;
import tz.co.nlolo.lifeplatform.benefitpayout.api.ProofOfLifeMethod;
import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * One dated amount owed, and the state machine that gets it paid.
 *
 * <p>Every transition is a method here rather than a setter on a service, so the rules are
 * unit-testable without a database and the two-person rule lives beside the CHECK that backs it.
 *
 * <p>The id is NOT set in the constructor: {@code @UuidGenerator} assigns it on persist, which
 * keeps {@code isNew()} true so JPA calls {@code persist()} rather than {@code merge()}. Setting
 * it here would make the returned view's id and the stored row's id diverge -- the trap
 * {@code SurrenderRequest} documents at length.
 */
@Entity
@Table(name = "payout_instalment", schema = "benefitpayout")
public class PayoutInstalment {

    /** States in which nothing has left the company, so the instalment may still be withdrawn. */
    private static final Set<InstalmentStatus> CANCELLABLE = EnumSet.of(
        InstalmentStatus.SCHEDULED, InstalmentStatus.DUE, InstalmentStatus.ON_HOLD, InstalmentStatus.REVIEWED);

    @Id
    @UuidGenerator
    @Column(name = "instalment_id")
    private UUID instalmentId;

    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private String kind;
    @Column(name = "row_order", nullable = false) private int rowOrder;
    @Column(name = "stream_id") private UUID streamId;
    @Column(name = "due_date", nullable = false) private LocalDate dueDate;
    @Column(name = "original_amount") private BigDecimal originalAmount;
    @Column(name = "current_amount") private BigDecimal currentAmount;
    @Column(nullable = false) private String currency = "TZS";
    @Column(name = "restatement_reason") private String restatementReason;
    @Column(nullable = false) private String status = InstalmentStatus.SCHEDULED.name();
    @Column(name = "status_reason") private String statusReason;
    @Column(name = "payee_ref") private String payeeRef;
    @Column(name = "proof_of_life_method") private String proofOfLifeMethod;
    @Column(name = "proof_of_life_document_id") private UUID proofOfLifeDocumentId;
    @Column(name = "reviewed_by") private String reviewedBy;
    @Column(name = "reviewed_at") private Instant reviewedAt;
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "payment_run_id") private UUID paymentRunId;
    @Column(name = "disbursement_id") private UUID disbursementId;
    @Column(nullable = false) private int attempts;

    /** Exactly-once for the drains: the winning write is exclusive, the loser is a no-op. */
    @Version private long version;

    protected PayoutInstalment() {}

    public PayoutInstalment(UUID tenantId, String policyNumber, PayoutKind kind, int rowOrder, UUID streamId,
                            LocalDate dueDate, BigDecimal amount, String currency) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.kind = kind.name();
        this.rowOrder = rowOrder;
        this.streamId = streamId;
        this.dueDate = dueDate;
        this.originalAmount = amount;
        this.currentAmount = amount;
        this.currency = currency;
    }

    public InstalmentStatus status() { return InstalmentStatus.valueOf(status); }

    public PayoutKind kind() { return PayoutKind.valueOf(kind); }

    /**
     * SCHEDULED -> DUE, or ON_HOLD when premiums are behind (decision Q3). A premium return learns
     * its amount here, because only now are the premiums collected a settled figure.
     */
    public void fallDue(boolean premiumsUpToDate, BigDecimal valuedAmount) {
        require(InstalmentStatus.SCHEDULED, "fall due");
        if (valuedAmount != null) {
            this.originalAmount = valuedAmount;
            this.currentAmount = valuedAmount;
        }
        if (premiumsUpToDate) {
            this.status = InstalmentStatus.DUE.name();
            this.statusReason = null;
        } else {
            hold("Premiums are not paid up to the due date");
        }
    }

    public void hold(String reason) {
        if (status() != InstalmentStatus.SCHEDULED && status() != InstalmentStatus.DUE) {
            throw new PayoutStateException("Payout " + instalmentId + " is " + status + " and cannot be held");
        }
        this.status = InstalmentStatus.ON_HOLD.name();
        this.statusReason = reason;
    }

    /** ON_HOLD -> DUE once whatever held it has gone. */
    public void release() {
        require(InstalmentStatus.ON_HOLD, "be released");
        this.status = InstalmentStatus.DUE.name();
        this.statusReason = null;
    }

    /**
     * DUE -> REVIEWED (decision Q2). A survival or income payout needs proof the life assured is
     * alive; a maturity or premium return does not, because it is owed by the calendar alone.
     */
    public void review(String reviewer, String payeeRef, ProofOfLifeMethod method, UUID documentId) {
        require(InstalmentStatus.DUE, "be reviewed");
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new PayoutStateException("A payout needs a payee reference");
        }
        if (needsProofOfLife() && method == null) {
            throw new PayoutStateException("A " + kind + " payout needs proof that the life assured is alive");
        }
        this.payeeRef = payeeRef;
        this.proofOfLifeMethod = method != null ? method.name() : null;
        this.proofOfLifeDocumentId = documentId;
        this.reviewedBy = reviewer;
        this.reviewedAt = Instant.now();
        this.status = InstalmentStatus.REVIEWED.name();
    }

    public boolean needsProofOfLife() {
        return kind() == PayoutKind.SURVIVAL || kind() == PayoutKind.INCOME;
    }

    /** REVIEWED -> APPROVED, by someone other than the reviewer (decision Q1). */
    public void approve(String approver) {
        require(InstalmentStatus.REVIEWED, "be approved");
        if (approver == null || approver.equals(reviewedBy)) {
            throw new PayoutStateException("A payout must be approved by someone other than the person who reviewed it ("
                + reviewedBy + ")");
        }
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
        this.status = InstalmentStatus.APPROVED.name();
        this.attempts++;
    }

    /**
     * DUE -> APPROVED inside an approved payment run (decision Q7): the run's approver is the
     * checker, because the stream itself was already approved by two people and the system -- not
     * a person -- assembled this batch.
     */
    public void approveInRun(UUID paymentRunId, String approver, String payeeRef) {
        require(InstalmentStatus.DUE, "be approved in a payment run");
        this.paymentRunId = paymentRunId;
        this.payeeRef = payeeRef;
        this.approvedBy = approver;
        this.approvedAt = Instant.now();
        this.status = InstalmentStatus.APPROVED.name();
        this.attempts++;
    }

    public void assignToRun(UUID paymentRunId) {
        require(InstalmentStatus.DUE, "join a payment run");
        this.paymentRunId = paymentRunId;
    }

    public void markPaid(UUID disbursementId) {
        require(InstalmentStatus.APPROVED, "be marked paid");
        this.disbursementId = disbursementId;
        this.status = InstalmentStatus.PAID.name();
    }

    public void markFailed() {
        require(InstalmentStatus.APPROVED, "be marked failed");
        this.status = InstalmentStatus.FAILED.name();
    }

    /** FAILED -> APPROVED for another attempt. The approval stands; only the payment is retried. */
    public void retry() {
        require(InstalmentStatus.FAILED, "be retried");
        this.status = InstalmentStatus.APPROVED.name();
        this.attempts++;
    }

    /**
     * Withdraw it, if nothing has left the company. Returns false -- a no-op, not a throw -- once
     * approved or later: a lapse arriving after a payout went out must not fail the lapse.
     */
    public boolean cancel(String reason) {
        if (!CANCELLABLE.contains(status())) {
            return false;
        }
        this.status = InstalmentStatus.CANCELLED.name();
        this.statusReason = reason;
        return true;
    }

    /** Reinstatement: an instalment cancelled by a lapse comes back as SCHEDULED. */
    public void restore() {
        require(InstalmentStatus.CANCELLED, "be restored");
        this.status = InstalmentStatus.SCHEDULED.name();
        this.statusReason = null;
    }

    /** Paid-up: the new figure goes BESIDE the original, never over it. */
    public void restate(BigDecimal newAmount, String reason) {
        if (!CANCELLABLE.contains(status())) {
            return;
        }
        this.currentAmount = newAmount;
        this.restatementReason = reason;
    }

    private void require(InstalmentStatus expected, String action) {
        if (status() != expected) {
            throw new PayoutStateException("Payout " + instalmentId + " is " + status + ", so it cannot " + action);
        }
    }

    public UUID getInstalmentId() { return instalmentId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getRowOrder() { return rowOrder; }
    public UUID getStreamId() { return streamId; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getOriginalAmount() { return originalAmount; }
    public BigDecimal getCurrentAmount() { return currentAmount; }
    public String getCurrency() { return currency; }
    public String getRestatementReason() { return restatementReason; }
    public String getStatusReason() { return statusReason; }
    public String getPayeeRef() { return payeeRef; }
    public String getProofOfLifeMethod() { return proofOfLifeMethod; }
    public String getReviewedBy() { return reviewedBy; }
    public String getApprovedBy() { return approvedBy; }
    public UUID getPaymentRunId() { return paymentRunId; }
    public int getAttempts() { return attempts; }
}
