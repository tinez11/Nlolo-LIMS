package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "beneficiary", schema = "policy")
public class Beneficiary {

    @Id
    @Column(name = "beneficiary_id")
    private UUID beneficiaryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "beneficiary_type", nullable = false)
    private String beneficiaryType;

    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "freeform_designee")
    private String freeformDesignee;

    @Column(name = "share_percent", nullable = false)
    private BigDecimal sharePercent;

    @Column(nullable = false)
    private boolean revocable = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Beneficiary() {}

    // Exactly-one-of(partyId, freeformDesignee) is validated by the caller (PolicyApiImpl,
    // Task 2) BEFORE construction -- this constructor trusts its inputs, matching the DB's own
    // chk_beneficiary_exactly_one_designation CHECK as a second, independent layer, not the
    // only layer (Global Constraints: hand-written application validation, not schema-only).
    public Beneficiary(UUID tenantId, String policyNumber, String beneficiaryType, UUID partyId,
                        String freeformDesignee, BigDecimal sharePercent, boolean revocable) {
        this.beneficiaryId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.beneficiaryType = beneficiaryType;
        this.partyId = partyId;
        this.freeformDesignee = freeformDesignee;
        this.sharePercent = sharePercent;
        this.revocable = revocable;
    }

    public UUID getBeneficiaryId() { return beneficiaryId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getBeneficiaryType() { return beneficiaryType; }
    public UUID getPartyId() { return partyId; }
    public String getFreeformDesignee() { return freeformDesignee; }
    public BigDecimal getSharePercent() { return sharePercent; }
    public boolean isRevocable() { return revocable; }
    public boolean isActive() { return active; }

    public void deactivate() { this.active = false; }
}
