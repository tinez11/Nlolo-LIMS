package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;

import java.math.BigDecimal;
import java.util.UUID;

/** One band of a UNIT_LINKED version's mortality table (V25): the annual cost of insurance per 1,000 of risk. */
@Entity
@Table(name = "unit_linked_mortality", schema = "product")
public class UnitLinkedMortalityEntity {
    @Id @Column(name = "unit_linked_mortality_id") private UUID unitLinkedMortalityId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "age_from", nullable = false) private int ageFrom;
    @Column(name = "age_to") private Integer ageTo;
    @Column(name = "sex") private String sex;
    @Column(name = "annual_rate_per_mille", nullable = false) private BigDecimal annualRatePerMille;

    protected UnitLinkedMortalityEntity() {}

    public UnitLinkedMortalityEntity(UUID tenantId, UUID productVersionId, UnitLinkedPlan.MortalityRow row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.ageFrom = row.ageFrom();
        this.ageTo = row.ageTo();
        this.sex = row.sex();
        this.annualRatePerMille = row.annualRatePerMille();
    }

    public UnitLinkedPlan.MortalityRow toRow() {
        return new UnitLinkedPlan.MortalityRow(ageFrom, ageTo, sex, annualRatePerMille);
    }
}
