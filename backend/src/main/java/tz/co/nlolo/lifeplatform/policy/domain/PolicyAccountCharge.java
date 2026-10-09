package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.UUID;

/** One account charge a savings policy was issued on (V41). */
@Entity
@Table(name = "policy_account_charge", schema = "policy")
public class PolicyAccountCharge {

    @Embeddable
    public record Key(@Column(name = "policy_number") String policyNumber, @Column(name = "charge_id") UUID chargeId)
        implements Serializable {}

    @EmbeddedId
    private Key key;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    protected PolicyAccountCharge() {}

    public PolicyAccountCharge(String policyNumber, UUID chargeId, UUID tenantId) {
        this.key = new Key(policyNumber, chargeId);
        this.tenantId = tenantId;
    }

    public UUID getChargeId() { return key.chargeId(); }
}
