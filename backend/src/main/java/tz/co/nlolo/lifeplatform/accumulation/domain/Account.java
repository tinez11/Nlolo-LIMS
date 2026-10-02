package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import tz.co.nlolo.lifeplatform.accumulation.api.AccountStatus;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One savings account per ACCOUNT-basis policy, and the RUNNING HEAD of its ledger.
 *
 * <p>{@code lastSeq} and {@code balance} let a new entry know its sequence and balance without
 * summing history. They are a copy of the last entry, kept in the same transaction -- and the
 * database's follows-trigger refuses any entry that does not add up from the one before, so the head
 * cannot silently drift from the ledger.
 */
@Entity
@Table(name = "account", schema = "accumulation")
public class Account {

    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_id", nullable = false) private UUID productId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "policyholder_party_id", nullable = false) private UUID policyholderPartyId;
    @Column(nullable = false) private String currency;
    @Column(name = "opened_on", nullable = false) private LocalDate openedOn;
    @Column(nullable = false) private String status = AccountStatus.OPEN.name();
    @Column(name = "closed_reason") private String closedReason;
    @Column(name = "closed_on") private LocalDate closedOn;
    @Column(name = "last_seq", nullable = false) private int lastSeq;
    @Column(nullable = false) private BigDecimal balance = BigDecimal.ZERO;
    @Column(name = "last_month_end") private LocalDate lastMonthEnd;
    @Version private long version;

    protected Account() {}

    public Account(UUID tenantId, String policyNumber, UUID productId, UUID productVersionId,
                   UUID policyholderPartyId, String currency, LocalDate openedOn) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.productId = productId;
        this.productVersionId = productVersionId;
        this.policyholderPartyId = policyholderPartyId;
        this.currency = currency;
        this.openedOn = openedOn;
    }

    public AccountStatus status() { return AccountStatus.valueOf(status); }

    /** Advance the head past one entry. Returns the entry's seq. */
    public int advance(BigDecimal amount) {
        BigDecimal next = balance.add(amount);
        if (next.signum() < 0) {
            throw new AccumulationStateException("Policy " + policyNumber + "'s account holds " + balance
                + " and cannot move by " + amount);
        }
        this.balance = next;
        return ++lastSeq;
    }

    public void requireOpen() {
        if (status() != AccountStatus.OPEN) {
            throw new AccumulationStateException("Policy " + policyNumber + "'s account is closed ("
                + closedReason + ")");
        }
    }

    public void close(String reason, LocalDate on) {
        this.status = AccountStatus.CLOSED.name();
        this.closedReason = reason;
        this.closedOn = on;
    }

    public void monthEndPostedThrough(LocalDate monthEnd) { this.lastMonthEnd = monthEnd; }

    /** A reinstated policy brings an EXHAUSTED account back, at the zero it closed on. */
    public void reopen() {
        if (!"EXHAUSTED".equals(closedReason)) {
            throw new AccumulationStateException("Policy " + policyNumber + "'s account closed on "
                + closedReason + " and cannot reopen");
        }
        this.status = AccountStatus.OPEN.name();
        this.closedReason = null;
        this.closedOn = null;
    }

    /** A matured deposit whose payment failed: the money is back, and waits for a payee again (plan §R7). */
    public void reopenAwaitingPayee() {
        if (!"MATURED".equals(closedReason)) {
            throw new AccumulationStateException("Policy " + policyNumber + "'s account closed on "
                + closedReason + " and cannot reopen for a payee");
        }
        this.status = AccountStatus.OPEN.name();
        this.closedReason = null;
        this.closedOn = null;
    }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public UUID getProductVersionId() { return productVersionId; }
    public UUID getPolicyholderPartyId() { return policyholderPartyId; }
    public String getCurrency() { return currency; }
    public LocalDate getOpenedOn() { return openedOn; }
    public String getClosedReason() { return closedReason; }
    public LocalDate getClosedOn() { return closedOn; }
    public int getLastSeq() { return lastSeq; }
    public BigDecimal getBalance() { return balance; }
    public LocalDate getLastMonthEnd() { return lastMonthEnd; }
}
