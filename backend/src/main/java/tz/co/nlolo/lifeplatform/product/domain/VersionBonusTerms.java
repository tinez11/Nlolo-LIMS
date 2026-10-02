package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;

import java.util.UUID;

/** A with-profits version's terms (product step 4). Absent for a non-participating version -- see V21. */
@Entity
@Table(name = "version_bonus_terms", schema = "product")
public class VersionBonusTerms {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "bonus_method", nullable = false) private String bonusMethod;
    @Column(name = "paid_up_participates", nullable = false) private boolean paidUpParticipates;
    @Column(name = "surrender_basis", nullable = false) private String surrenderBasis;

    protected VersionBonusTerms() {}

    public VersionBonusTerms(UUID tenantId, UUID productVersionId, String bonusMethod, boolean paidUpParticipates,
                             String surrenderBasis) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.bonusMethod = bonusMethod;
        this.paidUpParticipates = paidUpParticipates;
        this.surrenderBasis = surrenderBasis;
    }

    public String getBonusMethod() { return bonusMethod; }
    public boolean isPaidUpParticipates() { return paidUpParticipates; }
    public String getSurrenderBasis() { return surrenderBasis; }
}
