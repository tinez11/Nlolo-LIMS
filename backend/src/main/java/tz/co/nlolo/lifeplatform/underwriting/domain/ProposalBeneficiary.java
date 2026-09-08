package tz.co.nlolo.lifeplatform.underwriting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A beneficiary named on the proposal, before any policy exists to attach one to.
 *
 * <p>Its own table in the underwriting schema rather than a row in {@code policy.beneficiary},
 * for the plain reason that that table is keyed by {@code policy_number} and there is no policy
 * yet. The shape is identical on purpose: the issuance listener maps one to the other without
 * reinterpreting anything, and a reader comparing the two sees the same columns saying the same
 * things at two points in time.
 *
 * <p>Immutable after creation. A nomination is revised by re-taking the proposal, not by
 * editing the record of what the applicant signed — and once a policy exists,
 * {@code PUT /policies/{n}/beneficiaries} owns the designation.
 */
@Entity
@Table(name = "proposal_beneficiary", schema = "underwriting")
public class ProposalBeneficiary {

    @Id
    @GeneratedValue
    @Column(name = "proposal_beneficiary_id")
    private UUID proposalBeneficiaryId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "case_id", nullable = false)
    private UUID caseId;

    @Column(name = "beneficiary_type", nullable = false)
    private String beneficiaryType;

    @Column(name = "party_id")
    private UUID partyId;

    @Column(name = "freeform_designee")
    private String freeformDesignee;

    @Column(name = "share_percent", nullable = false)
    private BigDecimal sharePercent;

    @Column(name = "revocable", nullable = false)
    private boolean revocable = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ProposalBeneficiary() {}

    public ProposalBeneficiary(UUID tenantId, UUID caseId, String beneficiaryType, UUID partyId,
                                String freeformDesignee, BigDecimal sharePercent, boolean revocable) {
        this.tenantId = tenantId;
        this.caseId = caseId;
        this.beneficiaryType = beneficiaryType;
        this.partyId = partyId;
        this.freeformDesignee = freeformDesignee;
        this.sharePercent = sharePercent;
        this.revocable = revocable;
    }

    public UUID getProposalBeneficiaryId() { return proposalBeneficiaryId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getCaseId() { return caseId; }
    public String getBeneficiaryType() { return beneficiaryType; }
    public UUID getPartyId() { return partyId; }
    public String getFreeformDesignee() { return freeformDesignee; }
    public BigDecimal getSharePercent() { return sharePercent; }
    public boolean isRevocable() { return revocable; }
    public Instant getCreatedAt() { return createdAt; }
}
