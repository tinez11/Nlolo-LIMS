package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import tz.co.nlolo.lifeplatform.product.api.BonusSurrenderRow;

import java.math.BigDecimal;
import java.util.UUID;

/** One row of an OWN_SCALE version's bonus surrender values (V21). */
@Entity
@Table(name = "bonus_surrender_row", schema = "product")
public class BonusSurrenderEntry {
    @Id @UuidGenerator @Column(name = "bonus_surrender_row_id") private UUID id;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "from_completed_years", nullable = false) private int fromCompletedYears;
    @Column(name = "per_mille", nullable = false) private BigDecimal perMille;

    protected BonusSurrenderEntry() {}

    public BonusSurrenderEntry(UUID tenantId, UUID productVersionId, BonusSurrenderRow row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fromCompletedYears = row.fromCompletedYears();
        this.perMille = row.perMille();
    }

    public BonusSurrenderRow toRow() { return new BonusSurrenderRow(fromCompletedYears, perMille); }
}
