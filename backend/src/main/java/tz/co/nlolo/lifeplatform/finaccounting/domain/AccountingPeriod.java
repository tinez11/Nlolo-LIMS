package tz.co.nlolo.lifeplatform.finaccounting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.finaccounting.api.FinaccountingValidationException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStateException;
import tz.co.nlolo.lifeplatform.finaccounting.api.PeriodStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * An accounting period (IFRS 17 spec §5.4): OPEN, then CLOSING while the month-end steps run, then LOCKED. Reopening
 * a locked period needs a reason from one person and an approval from another. A period with no row is OPEN; the
 * first transition writes it. The posting guard (finaccounting V10) reads this table on every line.
 */
@Entity
@Table(name = "accounting_period", schema = "finaccounting")
@IdClass(AccountingPeriodId.class)
public class AccountingPeriod {

    @Id @Column(name = "tenant_id") private UUID tenantId;
    @Id @Column(name = "period") private String period;
    @Enumerated(EnumType.STRING) @Column(name = "status", nullable = false) private PeriodStatus status;
    @Column(name = "closing_started_by") private String closingStartedBy;
    @Column(name = "closing_started_at") private Instant closingStartedAt;
    @Column(name = "locked_by") private String lockedBy;
    @Column(name = "locked_at") private Instant lockedAt;
    @Column(name = "reopen_requested_by") private String reopenRequestedBy;
    @Column(name = "reopen_requested_at") private Instant reopenRequestedAt;
    @Column(name = "reopen_reason") private String reopenReason;
    @Column(name = "reopened_by") private String reopenedBy;
    @Column(name = "reopened_at") private Instant reopenedAt;
    @Version @Column(name = "version", nullable = false) private long version;

    protected AccountingPeriod() {}

    /** A period not yet touched: OPEN. */
    public static AccountingPeriod open(UUID tenantId, String period) {
        AccountingPeriod p = new AccountingPeriod();
        p.tenantId = tenantId;
        p.period = period;
        p.status = PeriodStatus.OPEN;
        return p;
    }

    public void startClosing(String by, Instant at) {
        if (status != PeriodStatus.OPEN) {
            throw new PeriodStateException("Period " + period + " is not open; it is " + status);
        }
        status = PeriodStatus.CLOSING;
        closingStartedBy = by;
        closingStartedAt = at;
    }

    public void lock(String by, Instant at) {
        if (status != PeriodStatus.CLOSING) {
            throw new PeriodStateException("Period " + period + " is not closing; it is " + status
                + ". Start closing it first");
        }
        status = PeriodStatus.LOCKED;
        lockedBy = by;
        lockedAt = at;
    }

    public void requestReopen(String reason, String by, Instant at) {
        if (status != PeriodStatus.LOCKED) {
            throw new PeriodStateException("Period " + period + " is not locked; it is " + status);
        }
        if (reason == null || reason.isBlank()) {
            throw new FinaccountingValidationException("Reopening a locked period needs a reason");
        }
        reopenRequestedBy = by;
        reopenRequestedAt = at;
        reopenReason = reason.trim();
        reopenedBy = null;
        reopenedAt = null;
    }

    /** A second person approves; the period is OPEN again. The request and the approval stay on the row. */
    public void approveReopen(String by, Instant at) {
        if (status != PeriodStatus.LOCKED || reopenRequestedBy == null) {
            throw new PeriodStateException("Period " + period + " has no reopening awaiting approval");
        }
        if (by == null || by.equals(reopenRequestedBy)) {
            throw new PeriodStateException("A second person approves reopening a period");
        }
        status = PeriodStatus.OPEN;
        reopenedBy = by;
        reopenedAt = at;
        closingStartedBy = null;
        closingStartedAt = null;
        lockedBy = null;
        lockedAt = null;
    }

    public UUID getTenantId() { return tenantId; }
    public String getPeriod() { return period; }
    public PeriodStatus getStatus() { return status; }
    public String getClosingStartedBy() { return closingStartedBy; }
    public Instant getClosingStartedAt() { return closingStartedAt; }
    public String getLockedBy() { return lockedBy; }
    public Instant getLockedAt() { return lockedAt; }
    public String getReopenRequestedBy() { return reopenRequestedBy; }
    public Instant getReopenRequestedAt() { return reopenRequestedAt; }
    public String getReopenReason() { return reopenReason; }
    public String getReopenedBy() { return reopenedBy; }
    public Instant getReopenedAt() { return reopenedAt; }
}
