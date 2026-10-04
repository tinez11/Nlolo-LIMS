package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/** An ANNUITY version's own terms (product step 5). Absent for every other version -- see V22. */
@Entity
@Table(name = "version_annuity_terms", schema = "product")
public class VersionAnnuityTerms {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(nullable = false) private String timing;
    @Column(name = "proof_of_life_interval_months", nullable = false) private int proofOfLifeIntervalMonths;
    @Column(name = "joint_age_difference_min") private Integer jointAgeDifferenceMin;
    @Column(name = "joint_age_difference_max") private Integer jointAgeDifferenceMax;
    @Column(name = "basis_reference", nullable = false) private String basisReference;
    @Column(name = "basis_date", nullable = false) private LocalDate basisDate;

    protected VersionAnnuityTerms() {}

    public VersionAnnuityTerms(UUID tenantId, UUID productVersionId, String timing, int proofOfLifeIntervalMonths,
                               Integer jointAgeDifferenceMin, Integer jointAgeDifferenceMax, String basisReference,
                               LocalDate basisDate) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.timing = timing;
        this.proofOfLifeIntervalMonths = proofOfLifeIntervalMonths;
        this.jointAgeDifferenceMin = jointAgeDifferenceMin;
        this.jointAgeDifferenceMax = jointAgeDifferenceMax;
        this.basisReference = basisReference;
        this.basisDate = basisDate;
    }

    public String getTiming() { return timing; }
    public int getProofOfLifeIntervalMonths() { return proofOfLifeIntervalMonths; }
    public Integer getJointAgeDifferenceMin() { return jointAgeDifferenceMin; }
    public Integer getJointAgeDifferenceMax() { return jointAgeDifferenceMax; }
    public String getBasisReference() { return basisReference; }
    public LocalDate getBasisDate() { return basisDate; }
}
