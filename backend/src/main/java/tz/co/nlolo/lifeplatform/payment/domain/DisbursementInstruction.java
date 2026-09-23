package tz.co.nlolo.lifeplatform.payment.domain;

import tz.co.nlolo.lifeplatform.payment.api.DisbursementMethod;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An outbound payout instruction with a real lifecycle (PENDING -> IN_DOUBT -> COMPLETED/FAILED,
 * or PENDING -> COMPLETED/FAILED directly), NOT an append-only ledger row -- see
 * db-migrations/payment/V2's section-1 comment for why the append-only REVOKE was dropped in M5
 * rather than the mutable status.
 *
 * <p>markCompleted/markFailed are deliberately IDEMPOTENT on re-entry into the same terminal
 * state and reject a conflicting one. That asymmetry is copied from the reserve/confirm/release
 * protocol in policy: PolicyApiImpl.confirmReservation explicitly REJECTS a duplicate confirm
 * ("must surface as a clear domain error rather than double-applying"), while releaseReservation
 * is an explicit idempotent no-op ("so a caller retrying after a network timeout ... doesn't get
 * a spurious error"). An at-least-once gateway callback is exactly that retrying caller, so a
 * repeat of the SAME outcome must be silent; a contradictory outcome must not be.
 *
 * <p><b>IN_DOUBT (review finding C2)</b> is a NON-terminal state meaning "we sent this to the rail
 * and do not know whether money moved" -- a read timeout, or an ACCEPTED response carrying no
 * gatewayReference to reconcile against. It is deliberately NOT the same thing as FAILED, which
 * means "the rail definitely did not pay": recording an unknown outcome as FAILED published
 * payment.DisbursementFailed and drove a real REVERSAL plus encumbrance release in policyloan --
 * a compensation for a payout that may well have succeeded. See db-migrations/payment/V4's
 * section-1 comment for the full write-up. Because IN_DOUBT is non-terminal, it is an accepted
 * SOURCE state for both terminal transitions below: that is precisely what makes the state
 * recoverable by a later genuine callback rather than merely better-labelled.
 */
@Entity
@Table(name = "disbursement_instruction", schema = "payment")
@IdClass(DisbursementInstruction.DisbursementInstructionId.class)
public class DisbursementInstruction {

    @Id
    @Column(name = "disbursement_id")
    private UUID disbursementId = UUID.randomUUID();

    @Id
    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "payee_ref", nullable = false)
    private String payeeRef;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency = "TZS";

    @Column(nullable = false)
    private String purpose;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "gateway_reference")
    private String gatewayReference;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    /** Which rail. Stored as the enum's name rather than as an {@code @Enumerated} so the column
     * stays a plain VARCHAR under a CHECK constraint, which is how {@code status} and
     * {@code purpose} are already modelled on this table. */
    @Column(nullable = false)
    private String method = DisbursementMethod.MOBILE_MONEY.name();

    @Column(name = "executed_by")
    private String executedBy;

    @Column(name = "executed_at")
    private Instant executedAt;

    @Version
    private long version;

    protected DisbursementInstruction() {}

    /** Mobile money — every disbursement this platform made before credit life. */
    public DisbursementInstruction(UUID tenantId, String idempotencyKey, String payeeRef, BigDecimal amount,
                                   String currency, String purpose, String sourceRef) {
        this(tenantId, idempotencyKey, payeeRef, amount, currency, purpose, sourceRef,
            DisbursementMethod.MOBILE_MONEY);
    }

    /**
     * An EFT is born AWAITING_EXECUTION rather than PENDING, and the difference is load-bearing.
     * PENDING means "handed to a rail, waiting to hear back" — a state a timeout sweep may
     * legitimately retry or mark IN_DOUBT. AWAITING_EXECUTION means "nothing has been handed to
     * anything; a person still has to move this money". Collapsing the two would let machinery
     * built for a gateway act on a row no gateway has ever seen.
     */
    public DisbursementInstruction(UUID tenantId, String idempotencyKey, String payeeRef, BigDecimal amount,
                                   String currency, String purpose, String sourceRef,
                                   DisbursementMethod method) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.payeeRef = payeeRef;
        this.amount = amount;
        this.currency = currency;
        this.purpose = purpose;
        this.sourceRef = sourceRef;
        this.method = method.name();
        if (method == DisbursementMethod.EFT) {
            this.status = "AWAITING_EXECUTION";
        }
    }

    public void markCompleted(String gatewayReference) {
        if ("COMPLETED".equals(status)) {
            return; // idempotent: a redelivered success callback is not an error
        }
        if (!isResolvable()) {
            throw new IllegalStateException("Disbursement " + disbursementId + " is " + status + ", cannot mark COMPLETED");
        }
        this.status = "COMPLETED";
        this.gatewayReference = gatewayReference;
    }

    public void markFailed(String gatewayReference) {
        if ("FAILED".equals(status)) {
            return; // idempotent, same reasoning as markCompleted
        }
        if (!isResolvable()) {
            throw new IllegalStateException("Disbursement " + disbursementId + " is " + status + ", cannot mark FAILED");
        }
        this.status = "FAILED";
        this.gatewayReference = gatewayReference;
    }

    /**
     * Records "sent to the rail, outcome unknown" (review finding C2). Non-terminal, and
     * deliberately NOT accompanied by any published event -- see
     * {@code PaymentApiImpl.markDisbursementInDoubt}.
     *
     * <p>Only PENDING -> IN_DOUBT is legal. Idempotent on repeat (the same indeterminate outcome
     * observed twice is not an error), and it must never pull a row BACK out of a terminal state:
     * once the rail has told us definitively, a later ambiguity does not un-tell us.
     * {@code gatewayReference} is accepted (and only ever written when non-null) because the one
     * indeterminate case that DOES carry a reference must not lose it -- and because overwriting a
     * previously-recorded reference with null would destroy the very correlation handle
     * {@code resolve_disbursement_tenant} needs.
     */
    public void markInDoubt(String gatewayReference) {
        if ("IN_DOUBT".equals(status)) {
            if (gatewayReference != null) {
                this.gatewayReference = gatewayReference;
            }
            return;
        }
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Disbursement " + disbursementId + " is " + status + ", cannot mark IN_DOUBT");
        }
        this.status = "IN_DOUBT";
        if (gatewayReference != null) {
            this.gatewayReference = gatewayReference;
        }
    }

    /** PENDING (never attempted, or attempted with no outcome yet) and IN_DOUBT (attempted,
     * outcome unknown) are the two NON-terminal states, so both are legal sources for a terminal
     * transition. Admitting IN_DOUBT here is the whole point of C2's fix: without it a genuine
     * SUCCESS/FAILED callback arriving later for an in-doubt payout would throw, and
     * MobileMoneyCallbackController would swallow that and ack 200, so the aggregator would stop
     * retrying and the row would be stranded forever. */
    private boolean isResolvable() {
        return "PENDING".equals(status) || "IN_DOUBT".equals(status);
    }

    public void assignToBatch(UUID batchId) { this.batchId = batchId; }

    public UUID getDisbursementId() { return disbursementId; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getTenantId() { return tenantId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPayeeRef() { return payeeRef; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPurpose() { return purpose; }
    public String getStatus() { return status; }
    public String getMethod() { return method; }
    public String getExecutedBy() { return executedBy; }
    public java.time.Instant getExecutedAt() { return executedAt; }

    /**
     * Finance moved the money in the bank portal and is recording that they did.
     *
     * <p>The bank reference goes in gatewayReference -- the same column a gateway callback
     * fills -- because it answers the same question: what does the OTHER side call this
     * transfer. Reconciliation asks that question without caring which rail answered.
     */
    public void markEftExecuted(String bankReference, String executedBy) {
        // The rail check comes FIRST, before the idempotent short-circuit. Caught by
        // EftDisbursementIntegrationTest: with the order reversed, a COMPLETED mobile-money
        // payout -- whose status is already COMPLETED -- was silently accepted as "confirming
        // twice" and returned quietly, so a person could claim to have executed a transfer the
        // gateway had made. Idempotency is only ever a property of the SAME operation repeated.
        if (!DisbursementMethod.EFT.name().equals(method)) {
            throw new IllegalStateException("Disbursement " + disbursementId + " is a " + method
                + " payout, which is not awaiting execution by anybody -- the rail completes it");
        }
        if ("COMPLETED".equals(status)) {
            return; // idempotent: confirming the same EFT twice is not an error
        }
        if (!"AWAITING_EXECUTION".equals(status)) {
            throw new IllegalStateException("Disbursement " + disbursementId + " is " + status
                + ", not awaiting execution");
        }
        if (bankReference == null || bankReference.isBlank()) {
            throw new IllegalArgumentException("Recording an executed transfer needs the bank reference for it");
        }
        if (executedBy == null || executedBy.isBlank()) {
            throw new IllegalArgumentException("Recording an executed transfer needs who executed it");
        }
        this.status = "COMPLETED";
        this.gatewayReference = bankReference;
        this.executedBy = executedBy;
        this.executedAt = java.time.Instant.now();
    }
    public String getGatewayReference() { return gatewayReference; }
    public UUID getBatchId() { return batchId; }
    public String getSourceRef() { return sourceRef; }

    public static class DisbursementInstructionId implements Serializable {
        private UUID disbursementId;
        private Instant createdAt;

        public DisbursementInstructionId() {}
        public DisbursementInstructionId(UUID disbursementId, Instant createdAt) {
            this.disbursementId = disbursementId;
            this.createdAt = createdAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof DisbursementInstructionId that)) return false;
            return Objects.equals(disbursementId, that.disbursementId) && Objects.equals(createdAt, that.createdAt);
        }

        @Override
        public int hashCode() { return Objects.hash(disbursementId, createdAt); }
    }
}
