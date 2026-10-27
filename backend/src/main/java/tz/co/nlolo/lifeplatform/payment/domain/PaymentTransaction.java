package tz.co.nlolo.lifeplatform.payment.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An inbound payment transaction with a real lifecycle (PENDING -> IN_DOUBT -> CONFIRMED/FAILED,
 * or PENDING -> CONFIRMED/FAILED directly), NOT an append-only ledger row -- see
 * db-migrations/payment/V2's section-1 comment for why the append-only REVOKE was dropped in M5
 * rather than the mutable status.
 *
 * <p><b>IN_DOUBT (review finding C2)</b> means "we sent this to the rail and do not know whether
 * money moved" and is deliberately distinct from FAILED ("the rail definitely did not collect").
 * See {@link DisbursementInstruction}'s javadoc and db-migrations/payment/V4's section-1 comment
 * for the full reasoning; the collection ledger carries the identical state for the identical
 * reason, with CONFIRMED as its success value rather than COMPLETED (V1's own asymmetry).
 *
 * <p>markConfirmed/markFailed are deliberately IDEMPOTENT on re-entry into the same terminal
 * state and reject a conflicting one. That asymmetry is copied from the reserve/confirm/release
 * protocol in policy: PolicyApiImpl.confirmReservation explicitly REJECTS a duplicate confirm
 * ("must surface as a clear domain error rather than double-applying"), while releaseReservation
 * is an explicit idempotent no-op ("so a caller retrying after a network timeout ... doesn't get
 * a spurious error"). An at-least-once gateway callback is exactly that retrying caller, so a
 * repeat of the SAME outcome must be silent; a contradictory outcome must not be.
 */
@Entity
@Table(name = "payment_transaction", schema = "payment")
@IdClass(PaymentTransaction.PaymentTransactionId.class)
public class PaymentTransaction {

    @Id
    @Column(name = "payment_transaction_id")
    private UUID paymentTransactionId = UUID.randomUUID();

    @Id
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "payer_ref", nullable = false)
    private String payerRef;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TZS";

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    /**
     * What the money is for (payment V9). PREMIUM -- an invoice, billing's -- for every collection
     * before product step 3; ACCOUNT_TOP_UP for money a customer adds to a savings account, whose
     * sourceRef is a top-up id, not an invoice. Carried on PaymentConfirmed/PaymentFailed so billing
     * can leave a confirmation that is not its own.
     */
    @Column(nullable = false)
    private String purpose = "PREMIUM";

    /**
     * How the money came in (payment V17, 2026-10-27): MOBILE_MONEY, the gateway's, for every collection it pulls;
     * CASH, BANK_DEPOSIT, CHEQUE, LIPA_NUMBER or TANQR for a receipt a person records.
     */
    @Column(nullable = false)
    private String method = "MOBILE_MONEY";

    /** A recorded receipt's own facts (payment V17); null on everything the gateway pulled. */
    @Column(name = "received_on")
    private java.time.LocalDate receivedOn;

    @Column(name = "recorded_by")
    private String recordedBy;

    @Column
    private String note;

    /** The agent's field receipt this records, where it records one -- it closes on approval. */
    @Column(name = "field_receipt_id")
    private UUID fieldReceiptId;

    @Column(name = "decided_by")
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_reason")
    private String decisionReason;

    /** The policy this money is for, where the request named one (payment V16, 2026-10-27). */
    @Column(name = "policy_number")
    private String policyNumber;

    @Version
    private long version;

    protected PaymentTransaction() {}

    public PaymentTransaction(UUID tenantId, String idempotencyKey, String payerRef, BigDecimal amount,
                               String currency, String sourceRef) {
        this(tenantId, idempotencyKey, payerRef, amount, currency, sourceRef, "PREMIUM");
    }

    public PaymentTransaction(UUID tenantId, String idempotencyKey, String payerRef, BigDecimal amount,
                               String currency, String sourceRef, String purpose) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.payerRef = payerRef;
        this.amount = amount;
        this.currency = currency;
        this.sourceRef = sourceRef;
        this.purpose = purpose;
    }

    public String getPurpose() { return purpose; }

    public String getPolicyNumber() { return policyNumber; }

    public String getMethod() { return method; }
    public java.time.LocalDate getReceivedOn() { return receivedOn; }
    public String getRecordedBy() { return recordedBy; }
    public String getNote() { return note; }
    public UUID getFieldReceiptId() { return fieldReceiptId; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionReason() { return decisionReason; }

    /**
     * A receipt a person records for money the gateway did not pull (2026-10-27). It pays nothing until a second
     * person approves it: AWAITING_APPROVAL is not a collection the rail is working, it is one finance is checking.
     */
    public static PaymentTransaction manualReceipt(UUID tenantId, String idempotencyKey, String method, String reference,
                                                   String payerRef, java.math.BigDecimal amount, String currency,
                                                   UUID invoiceId, String policyNumber, java.time.LocalDate receivedOn,
                                                   String note, UUID fieldReceiptId, String recordedBy) {
        PaymentTransaction t = new PaymentTransaction(tenantId, idempotencyKey, payerRef, amount, currency,
            invoiceId.toString(), "PREMIUM");
        t.method = method;
        t.gatewayReference = reference;
        t.policyNumber = policyNumber;
        t.receivedOn = receivedOn;
        t.note = note;
        t.fieldReceiptId = fieldReceiptId;
        t.recordedBy = recordedBy;
        t.status = "AWAITING_APPROVAL";
        return t;
    }

    /** The second person confirms it: the money now counts, and the invoice is paid from it. */
    public void approveReceipt(String by) {
        requireAwaitingApproval(by);
        this.status = "CONFIRMED";
        this.decidedBy = by;
        this.decidedAt = Instant.now();
    }

    /** The second person finds it wrong. It never paid anything, and stays as the record that it was refused. */
    public void rejectReceipt(String by, String reason) {
        requireAwaitingApproval(by);
        this.status = "REJECTED";
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.decisionReason = reason;
    }

    private void requireAwaitingApproval(String by) {
        if (!"AWAITING_APPROVAL".equals(status)) {
            throw new tz.co.nlolo.lifeplatform.payment.api.ReceiptRefusedException("This receipt is already "
                + ("CONFIRMED".equals(status) ? "approved" : "refused") + ": it is no longer waiting for a decision");
        }
        if (by != null && by.equals(recordedBy)) {
            throw new tz.co.nlolo.lifeplatform.payment.api.ReceiptRefusedException(
                "A receipt must be approved by someone other than the person who recorded it");
        }
    }

    public void notePolicy(String policyNumber) { this.policyNumber = policyNumber; }

    /**
     * The rail accepted a mobile-money request (2026-10-27, user decision: no live integration yet, so it is receipted
     * like every other method). Accepted is not paid: the request waits, as a receipt recorded by whoever asked for it,
     * until finance sees the money and a second person approves it.
     */
    public void awaitApproval(String gatewayReference, String requestedBy) {
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Payment " + paymentTransactionId + " is " + status + ", cannot await approval");
        }
        this.status = "AWAITING_APPROVAL";
        this.gatewayReference = gatewayReference;
        this.recordedBy = requestedBy;
    }

    /** A receipt the platform asked the payer's phone for, rather than one a person recorded by hand. */
    public boolean isRequested() {
        return "MOBILE_MONEY".equals(method);
    }

    public void markConfirmed(String gatewayReference) {
        if ("CONFIRMED".equals(status)) {
            return; // idempotent: a redelivered success callback is not an error
        }
        if (!isResolvable()) {
            throw new IllegalStateException("Payment " + paymentTransactionId + " is " + status + ", cannot mark CONFIRMED");
        }
        this.status = "CONFIRMED";
        this.gatewayReference = gatewayReference;
    }

    public void markFailed(String gatewayReference) {
        if ("FAILED".equals(status)) {
            return; // idempotent, same reasoning as markConfirmed
        }
        if (!isResolvable()) {
            throw new IllegalStateException("Payment " + paymentTransactionId + " is " + status + ", cannot mark FAILED");
        }
        this.status = "FAILED";
        this.gatewayReference = gatewayReference;
    }

    /** Records "sent to the rail, outcome unknown" (review finding C2). Non-terminal, publishes
     * nothing. Mirrors {@link DisbursementInstruction#markInDoubt} exactly -- same legal source
     * state (PENDING only, never a walk-back out of a terminal state), same idempotent repeat,
     * same never-null-over-an-existing-reference rule. */
    public void markInDoubt(String gatewayReference) {
        if ("IN_DOUBT".equals(status)) {
            if (gatewayReference != null) {
                this.gatewayReference = gatewayReference;
            }
            return;
        }
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Payment " + paymentTransactionId + " is " + status + ", cannot mark IN_DOUBT");
        }
        this.status = "IN_DOUBT";
        if (gatewayReference != null) {
            this.gatewayReference = gatewayReference;
        }
    }

    /** See {@link DisbursementInstruction}'s equivalent: PENDING and IN_DOUBT are the two
     * non-terminal states, so both are legal sources for a terminal transition -- which is what
     * makes an IN_DOUBT row recoverable by a later genuine callback. */
    private boolean isResolvable() {
        return "PENDING".equals(status) || "IN_DOUBT".equals(status);
    }

    public UUID getPaymentTransactionId() { return paymentTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getTenantId() { return tenantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPayerRef() { return payerRef; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getGatewayReference() { return gatewayReference; }
    public String getSourceRef() { return sourceRef; }

    public static class PaymentTransactionId implements Serializable {
        private UUID paymentTransactionId;
        private Instant createdAt;

        public PaymentTransactionId() {}
        public PaymentTransactionId(UUID paymentTransactionId, Instant createdAt) {
            this.paymentTransactionId = paymentTransactionId;
            this.createdAt = createdAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PaymentTransactionId that)) return false;
            return Objects.equals(paymentTransactionId, that.paymentTransactionId) && Objects.equals(createdAt, that.createdAt);
        }

        @Override
        public int hashCode() { return Objects.hash(paymentTransactionId, createdAt); }
    }
}
