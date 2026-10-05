package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions;

import java.math.BigDecimal;
import java.util.UUID;

/** One surrender-charge band of a UNIT_LINKED version (V26). */
@Entity
@Table(name = "unit_linked_surrender_charge", schema = "product")
public class UnitLinkedSurrenderChargeEntity {
    @Id @Column(name = "unit_linked_surrender_charge_id") private UUID unitLinkedSurrenderChargeId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "from_year", nullable = false) private int fromYear;
    @Column(name = "to_year") private Integer toYear;
    @Column(name = "charge_percent", nullable = false) private BigDecimal chargePercent;

    protected UnitLinkedSurrenderChargeEntity() {}

    public UnitLinkedSurrenderChargeEntity(UUID tenantId, UUID productVersionId, UnitLinkedOptions.SurrenderChargeBand band) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fromYear = band.fromYear();
        this.toYear = band.toYear();
        this.chargePercent = band.percent();
    }

    public UnitLinkedOptions.SurrenderChargeBand toBand() {
        return new UnitLinkedOptions.SurrenderChargeBand(fromYear, toYear, chargePercent);
    }
}
