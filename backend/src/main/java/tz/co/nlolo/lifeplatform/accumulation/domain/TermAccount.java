package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;
import tz.co.nlolo.lifeplatform.accumulation.api.TermAccountStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A term account's terms (V5): the rate the policy locked at sale and the day it matures. Both are fixed once written
 * -- a trigger refuses any change -- so maturing, and finding someone to pay, are the only changes it undergoes.
 *
 * <p>The maturity date is given, not derived here: it is the policy's (commencement + term), so the account and the
 * policy it belongs to can never disagree about when it ends.
 */
@Entity
@Table(name = "term_account", schema = "accumulation")
public class TermAccount {
    @Id @UuidGenerator @Column(name = "term_account_id") private java.util.UUID termAccountId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private java.util.UUID tenantId;
    @Column(name = "rate_percent", nullable = false) private BigDecimal ratePercent;
    @Column(name = "maturity_date", nullable = false) private LocalDate maturityDate;
    @Column(nullable = false) private String status = TermAccountStatus.RUNNING.name();
    @Column(name = "default_payee_ref") private String defaultPayeeRef;
    @Column(name = "matured_on") private LocalDate maturedOn;
    @Column(name = "payout_attempts", nullable = false) private int payoutAttempts;
    @Version private long version;

    protected TermAccount() {}

    public TermAccount(java.util.UUID tenantId, String policyNumber, BigDecimal ratePercent, LocalDate maturityDate) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.ratePercent = ratePercent;
        this.maturityDate = maturityDate;
    }

    public TermAccountStatus status() { return TermAccountStatus.valueOf(status); }

    /** The number the first contribution came from, kept as where the maturity goes. Never overwrites one. */
    public void collectedFrom(String payerRef) {
        if (defaultPayeeRef == null && payerRef != null && !payerRef.isBlank()) {
            this.defaultPayeeRef = payerRef;
        }
    }

    /** Matured and sent to a payee. From RUNNING, or from AWAITING_PAYEE once somebody is named. */
    public void paidOut(LocalDate on) {
        if (status() == TermAccountStatus.MATURED) {
            throw new AccumulationStateException("Term account " + policyNumber + " has already been paid out");
        }
        if (maturedOn == null) maturedOn = on;
        this.status = TermAccountStatus.MATURED.name();
    }

    /** Matured with nobody to pay, or its payment failed: the money waits on the account for a payee. */
    public void awaitPayee(LocalDate on) {
        if (maturedOn == null) maturedOn = on;
        this.status = TermAccountStatus.AWAITING_PAYEE.name();
    }

    /** Finance names who a waiting account is paid to; it becomes where any later attempt goes too. */
    public void payTo(String payeeRef) { this.defaultPayeeRef = payeeRef; }

    /** Each attempt to pay the matured money gets its own source reference, as a deposit's does. Returns its number. */
    public int recordPayoutAttempt() { return ++payoutAttempts; }

    public java.util.UUID getTermAccountId() { return termAccountId; }
    public String getPolicyNumber() { return policyNumber; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public String getDefaultPayeeRef() { return defaultPayeeRef; }
    public LocalDate getMaturedOn() { return maturedOn; }
}
