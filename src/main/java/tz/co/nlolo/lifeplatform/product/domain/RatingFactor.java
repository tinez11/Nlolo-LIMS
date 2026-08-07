package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "rating_table", schema = "product")
public class RatingFactor {

    @Id
    @UuidGenerator
    @Column(name = "rating_table_id")
    private UUID ratingTableId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "factor_type", nullable = false)
    private String factorType;

    @Column(nullable = false)
    private String band;

    @Column(nullable = false)
    private BigDecimal multiplier;

    protected RatingFactor() {}

    public RatingFactor(UUID tenantId, UUID productVersionId, String factorType, String band, BigDecimal multiplier) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.factorType = factorType;
        this.band = band;
        this.multiplier = multiplier;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getFactorType() { return factorType; }
    public String getBand() { return band; }
    public BigDecimal getMultiplier() { return multiplier; }
}
