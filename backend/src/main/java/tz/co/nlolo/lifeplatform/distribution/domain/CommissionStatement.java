package tz.co.nlolo.lifeplatform.distribution.domain;

import tz.co.nlolo.lifeplatform.distribution.api.DistributionValidationException;
import tz.co.nlolo.lifeplatform.distribution.api.InvalidAgentStateException;
import tz.co.nlolo.lifeplatform.distribution.api.StatementStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate root for {@code distribution.commission_statement}
 * (db-migrations/distribution/V1:57-68, V2 section 6). Every transition below follows the
 * idempotent-on-repeat / reject-on-conflict shape of {@code claims.domain.Claim}'s transitions
 * (itself following {@code payment.domain.DisbursementInstruction.markCompleted}): a repeat of the
 * SAME outcome is a silent no-op, a call from any other source state throws.
 *
 * <p>A statement is identified by (tenant, agent, period, currency) -- V2's
 * {@code ux_commission_statement_identity} -- so an agent selling in two currencies gets two
 * statements for the period.
 */
@Entity
@Table(name = "commission_statement", schema = "distribution")
public class CommissionStatement {

    @Id
    @UuidGenerator
    @Column(name = "statement_id")
    private UUID statementId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false)
    private String period;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "total_currency", nullable = false)
    private String totalCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatementStatus status = StatementStatus.OPEN;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Version
    private long version;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "payee_ref")
    private String payeeRef;

    @Column(name = "payout_idempotency_key")
    private String payoutIdempotencyKey;

    @Column(name = "payout_failure_reason")
    private String payoutFailureReason;

    // Money movement step 4 (distribution V11): who asked, and who released or refused the payout.
    @Column(name = "payout_requested_by")
    private String payoutRequestedBy;

    @Column(name = "payout_decided_by")
    private String payoutDecidedBy;

    @Column(name = "payout_decided_at")
    private Instant payoutDecidedAt;

    @Column(name = "payout_refusal_reason")
    private String payoutRefusalReason;

    @Column(name = "acted_alone", nullable = false)
    private boolean actedAlone;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    // IFRS 17 I3b (distribution V6, guide A-05): the withholding tax taken from the payout at the rate in force when
    // it was requested, and so what the agent is paid. Null rate: none was configured, nothing withheld.
    @Column(name = "withholding_rate")
    private BigDecimal withholdingRate;

    @Column(name = "withheld_amount", nullable = false)
    private BigDecimal withheldAmount = BigDecimal.ZERO;

    protected CommissionStatement() {}

    public CommissionStatement(UUID tenantId, UUID agentId, String period, String totalCurrency) {
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.period = period;
        this.totalCurrency = totalCurrency;
    }

    /** Monthly close: OPEN -> CLOSED. After this, accruals for the period attach to the NEXT
     * statement, so a late clawback lands in the current open period rather than rewriting a
     * closed one.
     *
     * <p><b>Known duplication, deliberate -- flag it, do not "clean it up".</b> The production
     * closer is the pg_cron SECURITY DEFINER function distribution.close_commission_statements()
     * (Task 9), because closing is a CROSS-TENANT sweep and a Java thread has no TenantContext
     * (RLS would show it zero rows). So this method has no production caller and the transition
     * rule effectively lives twice: as the SQL predicate, and here. It is kept because the domain
     * should still state its own rule, it is the only place the guard is unit-testable without a
     * database, and a future manual/staff close has somewhere correct to go. The psql test in
     * Task 9 is what keeps the two definitions honest with each other. */
    public void close(Instant closedAt) {
        if (status == StatementStatus.CLOSED) {
            return;
        }
        if (status != StatementStatus.OPEN) {
            throw new InvalidAgentStateException(
                "Statement " + statementId + " is " + status + ", cannot close");
        }
        this.status = StatementStatus.CLOSED;
        this.closedAt = closedAt;
    }

    /** Someone holding commission.pay asks to pay this statement. CLOSED or PAYOUT_FAILED -> AWAITING_PAYOUT_APPROVAL;
     * nothing reaches the rail until a second person releases it (money movement step 4, 2026-11-04).
     * PAYOUT_FAILED is a valid source: the whole point of that state is that staff can retry,
     * and a retry MUST use a new idempotency key (payment dedupes on the old one). */
    public void askForPayout(String idempotencyKey, String payeeRef, String requestedBy) {
        if (status == StatementStatus.AWAITING_PAYOUT_APPROVAL || status == StatementStatus.PAYOUT_REQUESTED) {
            return;
        }
        if (status != StatementStatus.CLOSED && status != StatementStatus.PAYOUT_FAILED) {
            throw new InvalidAgentStateException(
                "Statement " + statementId + " is " + status + ", cannot request payout");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new DistributionValidationException("A payout idempotency key is required");
        }
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new DistributionValidationException("A payeeRef is required to pay a statement");
        }
        if (totalAmount == null || totalAmount.signum() <= 0) {
            // A zero or negative total is a real, correct outcome when clawbacks meet or exceed
            // accruals -- but there is nothing to pay, and the rail would reject it anyway.
            throw new InvalidAgentStateException(
                "Statement " + statementId + " totals " + totalAmount + "; nothing to pay out");
        }
        this.status = StatementStatus.AWAITING_PAYOUT_APPROVAL;
        this.payoutIdempotencyKey = idempotencyKey;
        this.payeeRef = payeeRef;
        this.payoutRequestedBy = requestedBy;
        this.payoutDecidedBy = null;
        this.payoutDecidedAt = null;
        this.payoutRefusalReason = null;
        this.actedAlone = false;
        this.payoutFailureReason = null; // a fresh attempt clears the previous failure
    }

    /** The second person releases the payout (payouts.approve). AWAITING_PAYOUT_APPROVAL -> PAYOUT_REQUESTED; the caller
     * then asks payment to pay it. The person who asked may release it only with payouts.approve-own. */
    public void approvePayout(String by, boolean mayActAlone) {
        decidePayout(by, mayActAlone, "approved");
        this.status = StatementStatus.PAYOUT_REQUESTED;
    }

    /** The second person refuses the payout, saying why. AWAITING_PAYOUT_APPROVAL -> CLOSED, so it can be asked again. */
    public void refusePayout(String by, boolean mayActAlone, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DistributionValidationException("Say why the payout is refused");
        }
        decidePayout(by, mayActAlone, "refused");
        this.status = StatementStatus.CLOSED;
        this.payoutRefusalReason = reason.trim();
    }

    private void decidePayout(String by, boolean mayActAlone, String verb) {
        if (status != StatementStatus.AWAITING_PAYOUT_APPROVAL) {
            throw new InvalidAgentStateException(
                "Statement " + statementId + " is " + status + ", not waiting for its payout to be approved");
        }
        boolean ownRequest = by.equals(payoutRequestedBy);
        if (ownRequest && !mayActAlone) {
            throw new InvalidAgentStateException(
                "A commission payout must be " + verb + " by someone other than the person who asked for it");
        }
        this.actedAlone = ownRequest;
        this.payoutDecidedBy = by;
        this.payoutDecidedAt = Instant.now();
    }

    /** payment.DisbursementCompleted. PAYOUT_REQUESTED -> PAID. Terminal. */
    public void markPaid(Instant paidAt) {
        if (status == StatementStatus.PAID) {
            return;
        }
        if (status != StatementStatus.PAYOUT_REQUESTED) {
            throw new InvalidAgentStateException(
                "Statement " + statementId + " is " + status + ", cannot mark paid");
        }
        this.status = StatementStatus.PAID;
        this.paidAt = paidAt;
    }

    /** payment.DisbursementFailed. PAYOUT_REQUESTED -> PAYOUT_FAILED, reason preserved so the
     * statement lands on a staff retry worklist rather than being retried silently. */
    public void markPayoutFailed(String reason) {
        if (status != StatementStatus.PAYOUT_REQUESTED) {
            return; // a redelivered failure after a manual retry already moved it on
        }
        this.status = StatementStatus.PAYOUT_FAILED;
        this.payoutFailureReason = reason;
    }

    /** Recomputed from this statement's accruals whenever one is added. May legitimately be
     * negative or zero once clawbacks are involved -- see V2's own comment on why no positivity
     * CHECK exists on this column. */
    public void recomputeTotal(List<CommissionAccrual> accruals) {
        this.totalAmount = accruals.stream()
            .map(CommissionAccrual::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public UUID getStatementId() { return statementId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getAgentId() { return agentId; }
    public String getPeriod() { return period; }
    /**
     * Withholding tax on this payout (IFRS 17 I3b): {@code rate} a fraction (0.05), or null when none is configured --
     * then nothing is withheld, never a default. Set when the payout is requested; a retry re-reads the rate.
     */
    public void applyWithholding(BigDecimal rate) {
        this.withholdingRate = rate;
        this.withheldAmount = rate == null ? BigDecimal.ZERO
            : totalAmount.multiply(rate).setScale(2, java.math.RoundingMode.HALF_EVEN);
    }

    /** What the agent is paid: the total less the tax withheld. */
    public BigDecimal getNetAmount() { return totalAmount.subtract(withheldAmount); }
    public BigDecimal getWithholdingRate() { return withholdingRate; }
    public BigDecimal getWithheldAmount() { return withheldAmount; }

    public BigDecimal getTotalAmount() { return totalAmount; }
    public String getTotalCurrency() { return totalCurrency; }
    public StatementStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public String getPayeeRef() { return payeeRef; }
    public String getPayoutIdempotencyKey() { return payoutIdempotencyKey; }
    public String getPayoutFailureReason() { return payoutFailureReason; }
    public String getPayoutRequestedBy() { return payoutRequestedBy; }
    public String getPayoutDecidedBy() { return payoutDecidedBy; }
    public Instant getPayoutDecidedAt() { return payoutDecidedAt; }
    public String getPayoutRefusalReason() { return payoutRefusalReason; }
    public boolean isActedAlone() { return actedAlone; }
    public Instant getClosedAt() { return closedAt; }
    public Instant getPaidAt() { return paidAt; }
}
