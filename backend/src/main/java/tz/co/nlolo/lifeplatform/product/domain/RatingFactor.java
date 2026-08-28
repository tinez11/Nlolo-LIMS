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

    /**
     * Set for AGE rows and null for every other factor type — {@code
     * rating_table_age_bounds_shape} enforces exactly that. {@code ageTo} is INCLUSIVE,
     * matching {@code BaseRate}.
     *
     * <p>These are what underwriting rates on. {@code band} stays for AGE rows as the
     * human-readable label a product screen shows, but it is no longer what the platform
     * matches against: the bands actually in this database include the bare value "21",
     * which cannot be read as a range without guessing.
     */
    @Column(name = "age_from")
    private Integer ageFrom;

    @Column(name = "age_to")
    private Integer ageTo;

    protected RatingFactor() {}

    public RatingFactor(UUID tenantId, UUID productVersionId, String factorType, String band, BigDecimal multiplier) {
        this(tenantId, productVersionId, factorType, band, multiplier, null, null);
    }

    public RatingFactor(UUID tenantId, UUID productVersionId, String factorType, String band,
                         BigDecimal multiplier, Integer ageFrom, Integer ageTo) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.factorType = factorType;
        this.band = band;
        this.multiplier = multiplier;
        this.ageFrom = ageFrom;
        this.ageTo = ageTo;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getFactorType() { return factorType; }
    public String getBand() { return band; }
    public BigDecimal getMultiplier() { return multiplier; }
    public Integer getAgeFrom() { return ageFrom; }
    public Integer getAgeTo() { return ageTo; }
}
