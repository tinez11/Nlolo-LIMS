package tz.co.nlolo.lifeplatform.distribution.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What one accepted credit-life file will earn its lender, held until that file's invoice is paid (distribution V9,
 * 2026-10-30). The user: the bank pays the full premium, then its commission is sent -- so nothing is booked to a
 * statement, and nothing can become payable, until billing says the premium arrived.
 */
@Entity
@Table(name = "credit_life_file_commission", schema = "distribution")
public class CreditLifeFileCommission {

    @Id
    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "agent_id", nullable = false)
    private UUID agentId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    /** The file's whole premium: an early refund reverses commission in proportion to its share of this. */
    @Column(name = "file_premium", nullable = false)
    private BigDecimal filePremium;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "released_accrual_id")
    private UUID releasedAccrualId;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected CreditLifeFileCommission() {}

    public CreditLifeFileCommission(UUID tenantId, UUID submissionId, String policyNumber, UUID agentId,
                                    BigDecimal amount, String currency, BigDecimal filePremium) {
        this.tenantId = tenantId;
        this.submissionId = submissionId;
        this.policyNumber = policyNumber;
        this.agentId = agentId;
        this.amount = amount;
        this.currency = currency;
        this.filePremium = filePremium;
    }

    public void released(UUID accrualId) {
        this.releasedAccrualId = accrualId;
        this.releasedAt = Instant.now();
    }

    public boolean isReleased() { return releasedAccrualId != null; }
    public UUID getSubmissionId() { return submissionId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getAgentId() { return agentId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public BigDecimal getFilePremium() { return filePremium; }
    public UUID getReleasedAccrualId() { return releasedAccrualId; }
}
