package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** REQUESTED -> APPROVED -> PAID | FAILED. The money leaves the account at APPROVED (rule 2). */
@Entity
@Table(name = "withdrawal_request", schema = "accumulation")
public class WithdrawalRequest {
    @Id @UuidGenerator @Column(name = "withdrawal_id") private UUID withdrawalId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(name = "payee_ref", nullable = false) private String payeeRef;
    @Column(nullable = false) private String status = "REQUESTED";
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "disbursement_id") private UUID disbursementId;
    @Version private long version;

    protected WithdrawalRequest() {}

    public WithdrawalRequest(UUID tenantId, String policyNumber, BigDecimal amount, String currency, String payeeRef,
                             String requestedBy) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.payeeRef = payeeRef;
        this.requestedBy = requestedBy;
    }

    public void approve(String by) {
        requireStatus("REQUESTED", "approved");
        if (by.equals(requestedBy)) {
            throw new AccumulationStateException("A withdrawal must be approved by someone other than the person who requested it");
        }
        this.status = "APPROVED";
        this.approvedBy = by;
        this.approvedAt = Instant.now();
    }

    /** False when already settled -- a redelivered disbursement outcome changes nothing. */
    public boolean markPaid(UUID disbursementId) {
        if (!"APPROVED".equals(status)) return false;
        this.status = "PAID";
        this.disbursementId = disbursementId;
        return true;
    }

    public boolean markFailed(UUID disbursementId) {
        if (!"APPROVED".equals(status)) return false;
        this.status = "FAILED";
        this.disbursementId = disbursementId;
        return true;
    }

    private void requireStatus(String expected, String verb) {
        if (!expected.equals(status)) {
            throw new AccumulationStateException("This withdrawal is " + status.toLowerCase() + " and cannot be " + verb);
        }
    }

    public UUID getWithdrawalId() { return withdrawalId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPayeeRef() { return payeeRef; }
    public String getStatus() { return status; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
}
