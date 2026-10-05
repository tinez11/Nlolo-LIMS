package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;

import java.math.BigDecimal;
import java.util.UUID;

/** The least premium a UNIT_LINKED version takes at one frequency (V25). */
@Entity
@Table(name = "unit_linked_premium_minimum", schema = "product")
public class UnitLinkedPremiumMinimumEntity {
    @Id @Column(name = "unit_linked_premium_minimum_id") private UUID unitLinkedPremiumMinimumId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "frequency", nullable = false) private String frequency;
    @Column(name = "minimum_amount", nullable = false) private BigDecimal minimumAmount;

    protected UnitLinkedPremiumMinimumEntity() {}

    public UnitLinkedPremiumMinimumEntity(UUID tenantId, UUID productVersionId, UnitLinkedPlan.PremiumMinimum minimum) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.frequency = minimum.frequency();
        this.minimumAmount = minimum.amount();
    }

    public UnitLinkedPlan.PremiumMinimum toMinimum() {
        return new UnitLinkedPlan.PremiumMinimum(frequency, minimumAmount);
    }
}
