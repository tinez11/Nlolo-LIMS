package tz.co.nlolo.lifeplatform.accumulation.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.accumulation.api.AccumulationStateException;
import tz.co.nlolo.lifeplatform.accumulation.api.DepositPeriodStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One term of a fixed-term deposit (V3). Its rate and dates are fixed once written -- a trigger
 * refuses any change to them -- so ending is the only change a period undergoes.
 *
 * <p>The maturity date is given, not derived here: it is the policy's (commencement + its months,
 * plan §R16), so a period and the policy it belongs to can never disagree about when it ends.
 */
@Entity
@Table(name = "deposit_period", schema = "accumulation")
public class DepositPeriod {
    @Id @UuidGenerator @Column(name = "period_id") private UUID periodId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(nullable = false) private int seq;
    @Column(nullable = false) private BigDecimal principal;
    @Column(name = "term_months", nullable = false) private int termMonths;
    @Column(name = "rate_percent", nullable = false) private BigDecimal ratePercent;
    @Column(name = "rate_version_id", nullable = false) private UUID rateVersionId;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "maturity_date", nullable = false) private LocalDate maturityDate;
    @Column(nullable = false) private String status = DepositPeriodStatus.RUNNING.name();
    @Column(name = "interest_posted") private BigDecimal interestPosted;
    @Column(name = "closed_on") private LocalDate closedOn;
    @Column(name = "default_payee_ref") private String defaultPayeeRef;
    @Column(name = "payout_attempts", nullable = false) private int payoutAttempts;
    @Version private long version;

    protected DepositPeriod() {}

    public DepositPeriod(UUID tenantId, String policyNumber, int seq, BigDecimal principal, int termMonths,
                         BigDecimal ratePercent, UUID rateVersionId, LocalDate startDate, LocalDate maturityDate,
                         String defaultPayeeRef) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.seq = seq;
        this.principal = principal;
        this.termMonths = termMonths;
        this.ratePercent = ratePercent;
        this.rateVersionId = rateVersionId;
        this.startDate = startDate;
        this.maturityDate = maturityDate;
        this.defaultPayeeRef = defaultPayeeRef;
    }

    public DepositPeriodStatus status() { return DepositPeriodStatus.valueOf(status); }

    /** The only change a period may undergo: it ends, saying how, what interest it posted and when. */
    public void end(DepositPeriodStatus how, BigDecimal interest, LocalDate on) {
        if (status() != DepositPeriodStatus.RUNNING) {
            throw new AccumulationStateException("Deposit term " + seq + " on policy " + policyNumber + " has already ended ("
                + status + ")");
        }
        if (how == DepositPeriodStatus.RUNNING) {
            throw new IllegalArgumentException("A term ends as MATURED, TERMINATED or CANCELLED");
        }
        this.status = how.name();
        this.interestPosted = interest;
        this.closedOn = on;
    }

    /** Each attempt to pay the matured money gets its own source reference (plan §R7). Returns its number. */
    public int recordPayoutAttempt() { return ++payoutAttempts; }

    public UUID getPeriodId() { return periodId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public int getSeq() { return seq; }
    public BigDecimal getPrincipal() { return principal; }
    public int getTermMonths() { return termMonths; }
    public BigDecimal getRatePercent() { return ratePercent; }
    public UUID getRateVersionId() { return rateVersionId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public BigDecimal getInterestPosted() { return interestPosted; }
    public LocalDate getClosedOn() { return closedOn; }
    public String getDefaultPayeeRef() { return defaultPayeeRef; }
}
