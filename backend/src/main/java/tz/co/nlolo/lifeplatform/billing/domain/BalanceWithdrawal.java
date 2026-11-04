package tz.co.nlolo.lifeplatform.billing.domain;

import jakarta.persistence.*;
import tz.co.nlolo.lifeplatform.PayoutRoute;
import tz.co.nlolo.lifeplatform.billing.api.WithdrawalRefusedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money taken back off a policy's balance (billing V15, money movement step 3a). The same road as a savings
 * withdrawal ({@code accumulation.domain.WithdrawalRequest}): REQUESTED -> APPROVED -> PAID | FAILED, or REJECTED. A
 * withdrawal the platform raised when the policy ended starts at AWAITING_PAYEE: billing does not know who should be
 * paid once a policy has ended, least of all on a death, so finance names the payee first.
 *
 * <p>The money leaves the balance at APPROVED, as it leaves a savings account at approval; a transfer the rail refuses
 * puts it back.
 */
@Entity
@Table(name = "balance_withdrawal", schema = "billing")
public class BalanceWithdrawal {

    public static final String ASKED = "ASKED";
    public static final String POLICY_ENDED = "POLICY_ENDED";

    @Id @Column(name = "withdrawal_id") private UUID withdrawalId = UUID.randomUUID();
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Column(nullable = false) private String currency;
    @Column(nullable = false) private String status;
    @Column(nullable = false) private String reason;
    @Column private String method;
    @Column(name = "payee_ref") private String payeeRef;
    @Column(name = "payee_bank") private String payeeBank;
    @Column(name = "payee_branch") private String payeeBranch;
    @Column(name = "payee_account_name") private String payeeAccountName;
    @Column(name = "payee_account_number") private String payeeAccountNumber;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "rejected_by") private String rejectedBy;
    @Column(name = "rejected_at") private Instant rejectedAt;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "disbursement_id") private UUID disbursementId;
    /** Approved or refused by the person who asked, holding payouts.approve-own -- the two-person rule otherwise. */
    @Column(name = "acted_alone", nullable = false) private boolean actedAlone;
    @Version private long version;

    /** Set for one decision by a person allowed to act alone; never stored. */
    @Transient private boolean mayActAlone;

    protected BalanceWithdrawal() {}

    /** Someone asked for the money back, to this payee. */
    public static BalanceWithdrawal asked(UUID tenantId, String policyNumber, BigDecimal amount, String currency,
                                          PayoutRoute route, String by) {
        BalanceWithdrawal w = new BalanceWithdrawal(tenantId, policyNumber, amount, currency, ASKED, by);
        w.route(route);
        w.status = "REQUESTED";
        return w;
    }

    /** The policy ended with money on its balance: all of it, waiting for finance to say who receives it. */
    public static BalanceWithdrawal policyEnded(UUID tenantId, String policyNumber, BigDecimal amount, String currency) {
        BalanceWithdrawal w = new BalanceWithdrawal(tenantId, policyNumber, amount, currency, POLICY_ENDED, "system");
        w.status = "AWAITING_PAYEE";
        return w;
    }

    private BalanceWithdrawal(UUID tenantId, String policyNumber, BigDecimal amount, String currency, String reason,
                              String requestedBy) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A withdrawal is for an amount above zero");
        }
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.amount = amount;
        this.currency = currency;
        this.reason = reason;
        this.requestedBy = requestedBy;
    }

    /** Finance names who receives a withdrawal the platform raised; the person who names it is its requester. */
    public void namePayee(PayoutRoute route, String by) {
        requireStatus("AWAITING_PAYEE", "given a payee");
        route(route);
        this.requestedBy = by;
        this.status = "REQUESTED";
    }

    public void allowActingAlone() { this.mayActAlone = true; }

    public void approve(String by) {
        requireStatus("REQUESTED", "approved");
        requireSecondPerson(by, "approved");
        this.actedAlone = by.equals(requestedBy);
        this.status = "APPROVED";
        this.approvedBy = by;
        this.approvedAt = Instant.now();
    }

    public void reject(String by, String why) {
        if (!"REQUESTED".equals(status) && !"AWAITING_PAYEE".equals(status)) {
            throw new WithdrawalRefusedException("This withdrawal is " + status.toLowerCase() + " and cannot be refused");
        }
        if (why == null || why.isBlank()) throw new WithdrawalRefusedException("Say why the withdrawal is refused");
        requireSecondPerson(by, "refused");
        this.actedAlone = by.equals(requestedBy);
        this.status = "REJECTED";
        this.rejectedBy = by;
        this.rejectedAt = Instant.now();
        this.rejectionReason = why.trim();
    }

    /** False when already settled -- a redelivered outcome changes nothing. */
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

    public PayoutRoute route() {
        return new PayoutRoute(method, payeeRef, payeeBank, payeeBranch, payeeAccountName, payeeAccountNumber);
    }

    private void route(PayoutRoute route) {
        this.method = route.method();
        this.payeeRef = route.payee();
        this.payeeBank = route.bank();
        this.payeeBranch = route.branch();
        this.payeeAccountName = route.accountName();
        this.payeeAccountNumber = route.accountNumber();
    }

    private void requireSecondPerson(String by, String verb) {
        if (by.equals(requestedBy) && !mayActAlone) {
            throw new WithdrawalRefusedException("A withdrawal must be " + verb + " by someone other than the person who asked for it");
        }
    }

    private void requireStatus(String expected, String verb) {
        if (!expected.equals(status)) {
            throw new WithdrawalRefusedException("This withdrawal is " + status.toLowerCase() + " and cannot be " + verb);
        }
    }

    public UUID getWithdrawalId() { return withdrawalId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getStatus() { return status; }
    public String getReason() { return reason; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public String getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public String getRejectedBy() { return rejectedBy; }
    public String getRejectionReason() { return rejectionReason; }
    public boolean isActedAlone() { return actedAlone; }
}
