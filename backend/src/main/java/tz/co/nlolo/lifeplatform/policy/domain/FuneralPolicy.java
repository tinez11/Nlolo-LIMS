package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * A funeral policy's own facts (policy V35): the plan the family bought, and the spouse a takeover is
 * waiting on (plan R8). 1:1 with the policy; a row here IS the statement "this policy covers a family".
 */
@Entity
@Table(name = "funeral_policy", schema = "policy")
public class FuneralPolicy {
    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "plan_code", nullable = false) private String planCode;
    @Column(name = "awaiting_takeover_life_id") private UUID awaitingTakeoverLifeId;

    protected FuneralPolicy() {}

    public FuneralPolicy(UUID tenantId, String policyNumber, String planCode) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.planCode = planCode;
    }

    public void awaitTakeoverBy(UUID spouseLifeId) { this.awaitingTakeoverLifeId = spouseLifeId; }
    public void takeoverCompleted() { this.awaitingTakeoverLifeId = null; }

    public String getPolicyNumber() { return policyNumber; }
    public String getPlanCode() { return planCode; }
    public UUID getAwaitingTakeoverLifeId() { return awaitingTakeoverLifeId; }
}
