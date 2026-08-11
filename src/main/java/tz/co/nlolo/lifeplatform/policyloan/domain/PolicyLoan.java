package tz.co.nlolo.lifeplatform.policyloan.domain;

import tz.co.nlolo.lifeplatform.policyloan.api.LoanNotEligibleException;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "policy_loan", schema = "policyloan")
public class PolicyLoan {

    @Id
    @Column(name = "loan_id")
    private UUID loanId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "principal_amount", nullable = false)
    private BigDecimal principalAmount;

    @Column(name = "principal_currency", nullable = false)
    private String principalCurrency = "TZS";

    @Column(nullable = false)
    private String status = "RESERVED_PENDING_ORIGINATION";

    @Column(name = "originated_at")
    private Instant originatedAt;

    // M5: which policy-side loan value reservation backed this loan, so the
    // DisbursementFailed compensation path (markDisbursementFailed) knows what to release.
    // Nullable -- loans originated before M5 have none recorded.
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    protected PolicyLoan() {}

    public PolicyLoan(UUID tenantId, String policyNumber, BigDecimal principalAmount, String principalCurrency, String createdBy) {
        this.loanId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.principalAmount = principalAmount;
        this.principalCurrency = principalCurrency;
        this.createdBy = createdBy;
    }

    public UUID getLoanId() { return loanId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getPrincipalAmount() { return principalAmount; }
    public String getPrincipalCurrency() { return principalCurrency; }
    public String getStatus() { return status; }
    public Instant getOriginatedAt() { return originatedAt; }
    public UUID getReservationId() { return reservationId; }

    public void recordReservation(UUID reservationId) {
        this.reservationId = reservationId;
    }

    public void markOriginated() {
        if (!"RESERVED_PENDING_ORIGINATION".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be RESERVED_PENDING_ORIGINATION to originate (current: " + status + ")");
        }
        this.status = "ORIGINATED";
        this.originatedAt = Instant.now();
    }

    public void markDisbursementRequested() {
        if (!"ORIGINATED".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be ORIGINATED before disbursement can be requested (current: " + status + ")");
        }
        this.status = "DISBURSEMENT_REQUESTED";
    }

    public void markDisbursed() {
        if ("DISBURSED".equals(status)) {
            return; // idempotent: a redelivered payment.DisbursementCompleted is not an error
        }
        if (!"DISBURSEMENT_REQUESTED".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSEMENT_REQUESTED to mark disbursed (current: " + status + ")");
        }
        this.status = "DISBURSED";
    }

    /** M5: the DisbursementFailed leg. Terminal -- a failed disbursement is not retried
     * automatically, because a retry that the rail actually accepted the first time is a double
     * payout (see MobileMoneyGatewayAdapter's own no-retry rationale). Staff re-originate. */
    public void markDisbursementFailed() {
        if ("DISBURSEMENT_FAILED".equals(status)) {
            return;
        }
        if (!"DISBURSEMENT_REQUESTED".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSEMENT_REQUESTED to mark failed (current: " + status + ")");
        }
        this.status = "DISBURSEMENT_FAILED";
    }

    /** Idempotent on repeated repayments -- only the DISBURSED -> REPAYING edge is a real
     * transition; a loan already REPAYING stays REPAYING. */
    public void markRepaying() {
        if ("DISBURSED".equals(status)) {
            this.status = "REPAYING";
        }
    }

    public void markSettled() {
        this.status = "SETTLED";
    }

    public void markForcedLapseTriggered() {
        if (!"DISBURSED".equals(status) && !"REPAYING".equals(status)) {
            throw new LoanNotEligibleException("Loan " + loanId + " must be DISBURSED or REPAYING to force-lapse (current: " + status + ")");
        }
        this.status = "FORCED_LAPSE_TRIGGERED";
    }
}
