package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

/**
 * A fund in the insurer's register (unitlinked V1). Never deleted: a fund that takes no more money is CLOSED and
 * keeps pricing, valuing and selling the units already in it, and its code is never reused.
 */
@Entity
@Table(name = "fund", schema = "unitlinked")
public class Fund {

    @Id @Column(name = "fund_id") private UUID fundId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "code", nullable = false) private String code;
    @Column(name = "name", nullable = false) private String name;
    @Column(name = "currency", nullable = false) private String currency;
    @Column(name = "asset_class", nullable = false) private String assetClass;
    @Column(name = "annual_management_charge_percent", nullable = false) private BigDecimal annualManagementChargePercent;
    @Column(name = "cut_off_time", nullable = false) private LocalTime cutOffTime;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "closed_by") private String closedBy;
    @Column(name = "closed_at") private Instant closedAt;
    /* 2026-11-12, finance finding 126: a second person decides a new fund. */
    @Column(name = "decided_by") private String decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "decision_reason") private String decisionReason;
    @Column(name = "acted_alone", nullable = false) private boolean actedAlone;
    @Version @Column(name = "version", nullable = false) private long version;

    protected Fund() {}

    public Fund(UUID tenantId, String code, String name, String currency, String assetClass,
                BigDecimal annualManagementChargePercent, LocalTime cutOffTime, String createdBy, Instant now) {
        this.fundId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.code = code;
        this.name = name;
        this.currency = currency;
        this.assetClass = assetClass;
        this.annualManagementChargePercent = annualManagementChargePercent;
        this.cutOffTime = cutOffTime;
        // Proposed: no money, no price, until a second person approves it (finding 126).
        this.status = "PROPOSED";
        this.createdBy = createdBy;
        this.createdAt = now;
    }

    /** A second person opens it; its proposer only when they may act alone, which the row records. */
    public void approve(String by, boolean mayActAlone, Instant now) {
        requireProposed();
        boolean alone = by.equals(createdBy);
        if (alone && !mayActAlone) {
            throw new UnitLinkedStateException("You proposed fund " + code + "; a second person must approve it");
        }
        this.status = "OPEN";
        this.decidedBy = by;
        this.decidedAt = now;
        this.actedAlone = alone;
    }

    /** Rejected with its reason, or withdrawn by its proposer: it never takes money. */
    public void reject(String by, String reason, Instant now) {
        requireProposed();
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Say why the fund is rejected");
        }
        this.status = "REJECTED";
        this.decidedBy = by;
        this.decidedAt = now;
        this.decisionReason = reason.trim();
    }

    private void requireProposed() {
        if (!isProposed()) {
            throw new UnitLinkedStateException("Fund " + code + " is " + status.toLowerCase() + ", not waiting for approval");
        }
    }

    public boolean isProposed() { return "PROPOSED".equals(status); }
    public boolean isActedAlone() { return actedAlone; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionReason() { return decisionReason; }

    public void close(String by, Instant now) {
        if (!isOpen()) {
            throw new UnitLinkedStateException("Fund " + code + " is already closed");
        }
        this.status = "CLOSED";
        this.closedBy = by;
        this.closedAt = now;
    }

    public boolean isOpen() { return "OPEN".equals(status); }

    public UUID getFundId() { return fundId; }
    public UUID getTenantId() { return tenantId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCurrency() { return currency; }
    public String getAssetClass() { return assetClass; }
    public BigDecimal getAnnualManagementChargePercent() { return annualManagementChargePercent; }
    public LocalTime getCutOffTime() { return cutOffTime; }
    public String getStatus() { return status; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public String getClosedBy() { return closedBy; }
    public Instant getClosedAt() { return closedAt; }
}
