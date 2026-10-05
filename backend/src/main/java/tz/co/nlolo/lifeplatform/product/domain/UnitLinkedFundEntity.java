package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/** One register fund a UNIT_LINKED version offers, by code (V25, plan R2). */
@Entity
@Table(name = "unit_linked_fund", schema = "product")
public class UnitLinkedFundEntity {
    @Id @Column(name = "unit_linked_fund_id") private UUID unitLinkedFundId = UUID.randomUUID();
    @Column(name = "product_version_id", nullable = false) private UUID productVersionId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "fund_code", nullable = false) private String fundCode;

    protected UnitLinkedFundEntity() {}

    public UnitLinkedFundEntity(UUID tenantId, UUID productVersionId, String fundCode) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fundCode = fundCode;
    }

    public String getFundCode() { return fundCode; }
}
