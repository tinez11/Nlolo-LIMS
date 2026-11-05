package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;
import tz.co.nlolo.lifeplatform.billing.api.BalanceRefusedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Taking back a deposit whose money never really arrived -- a bounced cheque, a reversed mobile-money payment (billing
 * V16, money movement step 3b). REQUESTED -> CONFIRMED or REJECTED. One person asks, a second confirms, or the same
 * person holding collections.approve-own, recorded in acted_alone. Nothing moves until it is confirmed.
 */
@Entity
@Table(name = "deposit_reversal", schema = "billing")
public class DepositReversal {

    @Id @Column(name = "reversal_id") private UUID reversalId = UUID.randomUUID();
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "payment_id", nullable = false) private UUID paymentId;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(nullable = false) private String reason;
    @Column(nullable = false) private String status = "REQUESTED";
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "acted_alone", nullable = false) private boolean actedAlone;
    @Version private long version;

    @Transient private boolean mayActAlone;

    protected DepositReversal() {}

    public DepositReversal(BalanceEntry deposit, String reason, String requestedBy) {
        if (reason == null || reason.isBlank()) {
            throw new BalanceRefusedException("Say why the deposit is being reversed -- a bounced cheque, a reversed payment");
        }
        this.tenantId = deposit.getTenantId();
        this.policyNumber = deposit.getPolicyNumber();
        this.paymentId = deposit.getPaymentId();
        this.amount = deposit.getAmount();
        this.currency = deposit.getCurrency();
        this.reason = reason.trim();
        this.requestedBy = requestedBy;
    }

    public void allowActingAlone() { this.mayActAlone = true; }

    public void confirm(String by) {
        decide(by, "confirmed");
        this.status = "CONFIRMED";
    }

    public void reject(String by, String why) {
        if (why == null || why.isBlank()) throw new BalanceRefusedException("Say why the reversal is refused");
        decide(by, "refused");
        this.status = "REJECTED";
        this.rejectionReason = why.trim();
    }

    private void decide(String by, String verb) {
        if (!"REQUESTED".equals(status)) {
            throw new BalanceRefusedException("This reversal is " + status.toLowerCase() + " and cannot be " + verb);
        }
        if (by.equals(requestedBy) && !mayActAlone) {
            throw new BalanceRefusedException("A reversal must be " + verb + " by someone other than the person who asked for it");
        }
        this.actedAlone = by.equals(requestedBy);
        this.decidedBy = by;
        this.decidedAt = Instant.now();
    }

    public UUID getReversalId() { return reversalId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getPaymentId() { return paymentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public boolean isActedAlone() { return actedAlone; }
}
