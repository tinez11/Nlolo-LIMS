package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;

import java.math.BigDecimal;
import java.util.UUID;

/** One allocation band of a UNIT_LINKED version (V25). */
@Entity
@Table(name = "unit_linked_allocation_band", schema = "product")
public class UnitLinkedAllocationBandEntity {
    @Id @Column(name = "unit_linked_allocation_band_id") private UUID unitLinkedAllocationBandId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "from_year", nullable = false) private int fromYear;
    @Column(name = "to_year") private Integer toYear;
    @Column(name = "allocation_percent", nullable = false) private BigDecimal allocationPercent;

    protected UnitLinkedAllocationBandEntity() {}

    public UnitLinkedAllocationBandEntity(UUID tenantId, UUID productVersionId, UnitLinkedPlan.AllocationBand band) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fromYear = band.fromYear();
        this.toYear = band.toYear();
        this.allocationPercent = band.percent();
    }

    public UnitLinkedPlan.AllocationBand toBand() {
        return new UnitLinkedPlan.AllocationBand(fromYear, toYear, allocationPercent);
    }
}
