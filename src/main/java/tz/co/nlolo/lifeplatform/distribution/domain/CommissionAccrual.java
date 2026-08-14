package tz.co.nlolo.lifeplatform.distribution.domain;

import tz.co.nlolo.lifeplatform.distribution.api.TierType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code distribution.commission_accrual} 1:1 (db-migrations/distribution/V2 section 7) --
 * the per-event line items a {@link CommissionStatement} totals. Append-only in spirit (like
 * {@code policy.domain.Endorsement}): a clawback is a NEW row with {@code reversesAccrualId} set,
 * never a mutation of the row it reverses. {@code statementId} starts null and is attached once
 * the accrual is rolled into a statement -- see {@link #attachToStatement(UUID)}.
 *
 * <p>{@code policyNumber} is an opaque reference into {@code policy}, never an FK (V2:135), same
 * convention as every other cross-module reference on this platform.
 */
@Entity
@Table(name = "commission_accrual", schema = "distribution")
public class CommissionAccrual {

    @Id
    @UuidGenerator
    @Column(name = "accrual_id")
    private UUID accrualId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(name = "statement_id")
    private UUID statementId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier_type", nullable = false)
    private TierType tierType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private String period;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Column(name = "reverses_accrual_id")
    private UUID reversesAccrualId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    protected CommissionAccrual() {}

    public CommissionAccrual(UUID tenantId, UUID agentId, String policyNumber, TierType tierType,
                              BigDecimal amount, String currency, String period, String sourceRef,
                              UUID reversesAccrualId, String createdBy) {
        this.tenantId = tenantId;
        this.agentId = agentId;
        this.policyNumber = policyNumber;
        this.tierType = tierType;
        this.amount = amount;
        this.currency = currency;
        this.period = period;
        this.sourceRef = sourceRef;
        this.reversesAccrualId = reversesAccrualId;
        this.createdBy = createdBy;
    }

    /** Rolls this accrual into a statement once one exists for its (agent, period, currency). */
    public void attachToStatement(UUID statementId) { this.statementId = statementId; }

    public UUID getAccrualId() { return accrualId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getAgentId() { return agentId; }
    public UUID getStatementId() { return statementId; }
    public String getPolicyNumber() { return policyNumber; }
    public TierType getTierType() { return tierType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getPeriod() { return period; }
    public String getSourceRef() { return sourceRef; }
    public UUID getReversesAccrualId() { return reversesAccrualId; }
    public Instant getCreatedAt() { return createdAt; }
    public String getCreatedBy() { return createdBy; }
}
