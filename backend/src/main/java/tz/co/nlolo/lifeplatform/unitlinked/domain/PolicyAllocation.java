package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * One fund's share of a unit-linked policy's premiums (unitlinked V2), copied from the case's choice at issue. Its
 * presence is what makes a policy unit-linked to this module. Fixed in U1; changing it is U2.
 */
@Entity
@Table(name = "policy_allocation", schema = "unitlinked")
public class PolicyAllocation {
    @Id @Column(name = "policy_allocation_id") private UUID policyAllocationId = UUID.randomUUID();
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "fund_id", nullable = false) private UUID fundId;
    @Column(name = "percent", nullable = false) private int percent;

    protected PolicyAllocation() {}

    public PolicyAllocation(UUID tenantId, String policyNumber, UUID fundId, int percent) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.fundId = fundId;
        this.percent = percent;
    }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getFundId() { return fundId; }
    public int getPercent() { return percent; }
}
