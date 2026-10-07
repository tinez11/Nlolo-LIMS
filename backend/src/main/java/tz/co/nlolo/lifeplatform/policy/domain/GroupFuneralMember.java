package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * A group funeral scheme's main member's own facts (policy V38): the association's number for them, the beneficiary
 * they named. 1:1 with the policy_member row the bill counts.
 */
@Entity
@Table(name = "group_funeral_member", schema = "policy")
public class GroupFuneralMember {
    @Id @Column(name = "policy_member_id") private UUID policyMemberId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "association_reference", nullable = false) private String associationReference;
    @Column(name = "beneficiary_name") private String beneficiaryName;
    @Column(name = "beneficiary_relationship") private String beneficiaryRelationship;
    @Column(name = "beneficiary_phone") private String beneficiaryPhone;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    protected GroupFuneralMember() {}

    public GroupFuneralMember(UUID tenantId, String policyNumber, UUID policyMemberId, String associationReference,
                              String beneficiaryName, String beneficiaryRelationship, String beneficiaryPhone) {
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.policyMemberId = policyMemberId;
        this.associationReference = associationReference;
        this.beneficiaryName = beneficiaryName;
        this.beneficiaryRelationship = beneficiaryRelationship;
        this.beneficiaryPhone = beneficiaryPhone;
    }

    /**
     * The spouse took the family over after the main member's death: the beneficiary the dead member named is theirs
     * no longer to have named, so it is cleared until the new main member names one.
     */
    public void takenOver() {
        this.beneficiaryName = null;
        this.beneficiaryRelationship = null;
        this.beneficiaryPhone = null;
    }

    public UUID getPolicyMemberId() { return policyMemberId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getAssociationReference() { return associationReference; }
    public String getBeneficiaryName() { return beneficiaryName; }
    public String getBeneficiaryRelationship() { return beneficiaryRelationship; }
    public String getBeneficiaryPhone() { return beneficiaryPhone; }
}
