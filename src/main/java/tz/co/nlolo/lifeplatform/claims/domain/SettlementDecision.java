package tz.co.nlolo.lifeplatform.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Maps {@code claims.settlement_decision} 1:1. Append-only audit trail of every approve/reject
 * decision -- {@code decided_by} is a CLAIMS_MANAGER, deliberately distinct from
 * {@code claim_assessment.assessor} for separation of duties (see
 * {@code ClaimAssessmentRepository.existsByClaimIdAndTenantIdAndAssessor}). No {@code @Version}:
 * no version column, and this row is never mutated after creation. */
@Entity
@Table(name = "settlement_decision", schema = "claims")
public class SettlementDecision {

    @Id
    @UuidGenerator
    @Column(name = "settlement_decision_id")
    private UUID settlementDecisionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "claim_id", nullable = false)
    private UUID claimId;

    @Column(name = "decided_by", nullable = false)
    private String decidedBy;

    @Column(nullable = false)
    private boolean approved;

    @Column(name = "approved_amount")
    private BigDecimal approvedAmount;

    @Column(name = "approved_currency")
    private String approvedCurrency;

    @Column(name = "rejection_reason")
    private String rejectionReason;

    @Column(name = "payee_ref")
    private String payeeRef;

    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt = Instant.now();

    protected SettlementDecision() {}

    public SettlementDecision(UUID tenantId, UUID claimId, String decidedBy, boolean approved,
                               BigDecimal approvedAmount, String approvedCurrency, String rejectionReason,
                               String payeeRef) {
        this.tenantId = tenantId;
        this.claimId = claimId;
        this.decidedBy = decidedBy;
        this.approved = approved;
        this.approvedAmount = approvedAmount;
        this.approvedCurrency = approvedCurrency;
        this.rejectionReason = rejectionReason;
        this.payeeRef = payeeRef;
    }

    public UUID getSettlementDecisionId() { return settlementDecisionId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getClaimId() { return claimId; }
    public String getDecidedBy() { return decidedBy; }
    public boolean isApproved() { return approved; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public String getApprovedCurrency() { return approvedCurrency; }
    public String getRejectionReason() { return rejectionReason; }
    public String getPayeeRef() { return payeeRef; }
    public Instant getDecidedAt() { return decidedAt; }
}
