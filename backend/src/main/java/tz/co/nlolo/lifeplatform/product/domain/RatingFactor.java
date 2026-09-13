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

    /**
     * Set for SUM_ASSURED_BAND rows and null for every other factor type (V9) — {@code
     * rating_table_sum_assured_bounds_shape} enforces exactly that, and {@code sumAssuredTo} is
     * INCLUSIVE like every other bound on this platform.
     *
     * <p>The same fix as {@code ageFrom}/{@code ageTo} above, for the same defect. A
     * SUM_ASSURED_BAND was matched by EXACT STRING against three values hardcoded in
     * underwriting — 'LOW', 'MEDIUM', 'HIGH' — while the author typed a band into a free-text
     * box. A real published product carried '5000000', matched none of them, and priced every
     * policy as though the factor did not exist.
     *
     * <p>NULL on a row published before V9. Such a row resolves for no sum assured at all, which
     * is exactly what it did before: the string it was matched on could never be produced either.
     */
    @Column(name = "sum_assured_from")
    private BigDecimal sumAssuredFrom;

    @Column(name = "sum_assured_to")
    private BigDecimal sumAssuredTo;

    protected RatingFactor() {}

    public RatingFactor(UUID tenantId, UUID productVersionId, String factorType, String band, BigDecimal multiplier) {
        this(tenantId, productVersionId, factorType, band, multiplier, null, null);
    }

    public RatingFactor(UUID tenantId, UUID productVersionId, String factorType, String band,
                         BigDecimal multiplier, Integer ageFrom, Integer ageTo) {
        this(tenantId, productVersionId, factorType, band, multiplier, ageFrom, ageTo, null, null);
    }

    public RatingFactor(UUID tenantId, UUID productVersionId, String factorType, String band,
                         BigDecimal multiplier, Integer ageFrom, Integer ageTo,
                         BigDecimal sumAssuredFrom, BigDecimal sumAssuredTo) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.factorType = factorType;
        this.band = band;
        this.multiplier = multiplier;
        this.ageFrom = ageFrom;
        this.ageTo = ageTo;
        this.sumAssuredFrom = sumAssuredFrom;
        this.sumAssuredTo = sumAssuredTo;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getFactorType() { return factorType; }
    public String getBand() { return band; }
    public BigDecimal getMultiplier() { return multiplier; }
    public Integer getAgeFrom() { return ageFrom; }
    public Integer getAgeTo() { return ageTo; }
    public BigDecimal getSumAssuredFrom() { return sumAssuredFrom; }
    public BigDecimal getSumAssuredTo() { return sumAssuredTo; }
}
