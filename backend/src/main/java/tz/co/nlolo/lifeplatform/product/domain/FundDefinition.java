package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "fund_definition", schema = "product")
public class FundDefinition {

    @Id
    @UuidGenerator
    @Column(name = "fund_definition_id")
    private UUID fundDefinitionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "fund_code", nullable = false)
    private String fundCode;

    @Column(name = "current_nav", nullable = false)
    private BigDecimal currentNav;

    @Column(name = "nav_currency", nullable = false)
    private String navCurrency = "TZS";

    protected FundDefinition() {}

    public FundDefinition(UUID tenantId, UUID productVersionId, String fundCode, BigDecimal currentNav) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.fundCode = fundCode;
        this.currentNav = currentNav;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getFundCode() { return fundCode; }
    public BigDecimal getCurrentNav() { return currentNav; }
}
