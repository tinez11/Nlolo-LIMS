package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanOption;

import java.util.UUID;

/** One plan a FUNERAL version offers (V24). */
@Entity
@Table(name = "funeral_plan", schema = "product")
public class FuneralPlanEntity {
    @Id @Column(name = "funeral_plan_id") private UUID funeralPlanId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "plan_code", nullable = false) private String planCode;
    @Column(nullable = false) private String name;

    protected FuneralPlanEntity() {}

    public FuneralPlanEntity(UUID tenantId, UUID productVersionId, FuneralPlanOption option) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.planCode = option.planCode();
        this.name = option.name();
    }

    public FuneralPlanOption toOption() {
        return new FuneralPlanOption(planCode, name);
    }
}
