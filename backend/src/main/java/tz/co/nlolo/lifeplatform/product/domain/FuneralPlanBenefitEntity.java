package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.FuneralPlanBenefit;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.math.BigDecimal;
import java.util.UUID;

/** What one plan pays for one role (V24). */
@Entity
@Table(name = "funeral_plan_benefit", schema = "product")
public class FuneralPlanBenefitEntity {
    @Id @Column(name = "funeral_plan_benefit_id") private UUID funeralPlanBenefitId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "plan_code", nullable = false) private String planCode;
    @Column(nullable = false) private String role;
    @Column(nullable = false) private BigDecimal benefit;

    protected FuneralPlanBenefitEntity() {}

    public FuneralPlanBenefitEntity(UUID tenantId, UUID productVersionId, FuneralPlanBenefit row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.planCode = row.planCode();
        this.role = row.role().name();
        this.benefit = row.benefit();
    }

    public FuneralPlanBenefit toBenefit() {
        return new FuneralPlanBenefit(planCode, FuneralRole.valueOf(role), benefit);
    }
}
