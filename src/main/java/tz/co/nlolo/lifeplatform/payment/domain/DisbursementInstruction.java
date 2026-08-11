package tz.co.nlolo.lifeplatform.payment.domain;

import jakarta.persistence.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An outbound payout instruction with a real lifecycle (PENDING -> COMPLETED/FAILED), NOT an
 * append-only ledger row -- see db-migrations/payment/V2's section-1 comment for why the
 * append-only REVOKE was dropped in M5 rather than the mutable status.
 *
 * <p>markCompleted/markFailed are deliberately IDEMPOTENT on re-entry into the same terminal
 * state and reject a conflicting one. That asymmetry is copied from the reserve/confirm/release
 * protocol in policy: PolicyApiImpl.confirmReservation explicitly REJECTS a duplicate confirm
 * ("must surface as a clear domain error rather than double-applying"), while releaseReservation
 * is an explicit idempotent no-op ("so a caller retrying after a network timeout ... doesn't get
 * a spurious error"). An at-least-once gateway callback is exactly that retrying caller, so a
 * repeat of the SAME outcome must be silent; a contradictory outcome must not be.
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

    @Version
    private long version;

    protected DisbursementInstruction() {}

    public DisbursementInstruction(UUID tenantId, String idempotencyKey, String payeeRef, BigDecimal amount,
                                   String currency, String purpose, String sourceRef) {
        this.tenantId = tenantId;
        this.idempotencyKey = idempotencyKey;
        this.payeeRef = payeeRef;
        this.amount = amount;
        this.currency = currency;
        this.purpose = purpose;
        this.sourceRef = sourceRef;
    }

    public void markCompleted(String gatewayReference) {
        if ("COMPLETED".equals(status)) {
            return; // idempotent: a redelivered success callback is not an error
        }
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Disbursement " + disbursementId + " is " + status + ", cannot mark COMPLETED");
        }
        this.status = "COMPLETED";
        this.gatewayReference = gatewayReference;
    }

    public void markFailed(String gatewayReference) {
        if ("FAILED".equals(status)) {
            return; // idempotent, same reasoning as markCompleted
        }
        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("Disbursement " + disbursementId + " is " + status + ", cannot mark FAILED");
        }
        this.status = "FAILED";
        this.gatewayReference = gatewayReference;
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
