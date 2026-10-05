package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import tz.co.nlolo.lifeplatform.product.api.FuneralPremiumRow;
import tz.co.nlolo.lifeplatform.product.api.FuneralRole;

import java.math.BigDecimal;
import java.util.UUID;

/** One row of a FUNERAL version's premium table (V24). */
@Entity
@Table(name = "funeral_premium", schema = "product")
public class FuneralPremiumEntity {
    @Id @Column(name = "funeral_premium_id") private UUID funeralPremiumId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "plan_code", nullable = false) private String planCode;
    @Column(nullable = false) private String role;
    @Column(name = "age_from", nullable = false) private int ageFrom;
    @Column(name = "age_to", nullable = false) private int ageTo;
    @Column(name = "yearly_premium", nullable = false) private BigDecimal yearlyPremium;

    protected FuneralPremiumEntity() {}

    public FuneralPremiumEntity(UUID tenantId, UUID productVersionId, FuneralPremiumRow row) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.planCode = row.planCode();
        this.role = row.role().name();
        this.ageFrom = row.ageFrom();
        this.ageTo = row.ageTo();
        this.yearlyPremium = row.yearlyPremium();
    }

    public FuneralPremiumRow toRow() {
        return new FuneralPremiumRow(planCode, FuneralRole.valueOf(role), ageFrom, ageTo, yearlyPremium);
    }
}
