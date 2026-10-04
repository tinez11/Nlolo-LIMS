package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.VestingTerms;

import java.math.BigDecimal;
import java.util.UUID;

/** A deferred annuity version's vesting terms (product step 5, D2). Absent for every other version -- see V23. */
@Entity
@Table(name = "version_vesting_terms", schema = "product")
public class VersionVestingTerms {
    @Id @Column(name = "product_version_id") private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "min_vesting_age", nullable = false) private int minVestingAge;
    @Column(name = "max_vesting_age", nullable = false) private int maxVestingAge;
    @Column(name = "default_form_code", nullable = false) private String defaultFormCode;
    @Column(name = "default_frequency", nullable = false) private String defaultFrequency;
    @Column(name = "max_commutation_percent", nullable = false) private BigDecimal maxCommutationPercent;
    @Column(name = "surrender_before_vesting", nullable = false) private boolean surrenderBeforeVesting;

    protected VersionVestingTerms() {}

    public VersionVestingTerms(UUID tenantId, UUID productVersionId, VestingTerms terms) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.minVestingAge = terms.minVestingAge();
        this.maxVestingAge = terms.maxVestingAge();
        this.defaultFormCode = terms.defaultFormCode();
        this.defaultFrequency = terms.defaultFrequency();
        this.maxCommutationPercent = terms.maxCommutationPercent();
        this.surrenderBeforeVesting = terms.surrenderBeforeVesting();
    }

    public VestingTerms toTerms() {
        return new VestingTerms(minVestingAge, maxVestingAge, defaultFormCode, defaultFrequency, maxCommutationPercent,
            surrenderBeforeVesting);
    }
}
