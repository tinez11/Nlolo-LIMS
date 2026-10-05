package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A unit-linked policy that is leaving (unitlinked V2): no charges are taken and no premium is invested while it
 * is frozen -- a death registered, a surrender approved, a maturity, a lapse, a free-look, or a fund exhausted.
 */
@Entity
@Table(name = "frozen_policy", schema = "unitlinked")
public class FrozenPolicy {

    public enum Reason { DEATH, SURRENDER, MATURITY, FREE_LOOK, LAPSE, EXHAUSTED }

    @Id @Column(name = "policy_number") private String policyNumber;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "reason", nullable = false) private String reason;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Column(name = "frozen_at", nullable = false) private Instant frozenAt;

    protected FrozenPolicy() {}

    public FrozenPolicy(UUID tenantId, String policyNumber, Reason reason, String sourceRef, Instant now) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.reason = reason.name();
        this.sourceRef = sourceRef;
        this.frozenAt = now;
    }

    public String getPolicyNumber() { return policyNumber; }
    public UUID getTenantId() { return tenantId; }
    public Reason getReason() { return Reason.valueOf(reason); }
    public String getSourceRef() { return sourceRef; }
    public Instant getFrozenAt() { return frozenAt; }
}
