package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The attached-bonus projection (product step 4, V32). Written only by restateAttachedBonus. */
@Entity
@Table(name = "policy_bonus", schema = "policy")
public class PolicyBonus {
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "attached_bonus_amount", nullable = false) private BigDecimal attachedBonusAmount;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    protected PolicyBonus() {}

    public PolicyBonus(String policyNumber, UUID tenantId) {
        this.policyNumber = policyNumber;
        this.tenantId = tenantId;
        this.attachedBonusAmount = BigDecimal.ZERO;
    }

    public void restate(BigDecimal amount) {
        this.attachedBonusAmount = amount;
        this.updatedAt = Instant.now();
    }

    public BigDecimal getAttachedBonusAmount() { return attachedBonusAmount; }
}
