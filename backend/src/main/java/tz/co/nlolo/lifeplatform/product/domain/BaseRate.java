package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One cell of a product version's base rate table: the annual rate per 1,000 of
 * sum assured for a given (age range, sex, smoker status).
 *
 * The base a premium is computed FROM. {@link RatingFactor}'s multipliers apply
 * on top of it for occupation class and sum-assured band, so those dimensions
 * keep pricing independently.
 *
 * The age range is structured rather than a band string, and `ageTo` is
 * INCLUSIVE. V2 of this table used a free-text band, which would have required
 * parsing "18-25" to resolve an applicant's age -- see
 * V3__base_rate_structured_age.sql for why that was wrong.
 *
 * Deliberately not called a mortality table -- see V2__base_rate_table.sql.
 */
@Entity
@Table(name = "base_rate_table", schema = "product")
public class BaseRate {

    @Id
    @UuidGenerator
    @Column(name = "base_rate_id")
    private UUID baseRateId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "age_from", nullable = false)
    private int ageFrom;

    @Column(name = "age_to", nullable = false)
    private int ageTo;

    @Column(nullable = false)
    private String sex;

    @Column(name = "smoker_status", nullable = false)
    private String smokerStatus;

    @Column(name = "rate_per_mille", nullable = false)
    private BigDecimal ratePerMille;

    protected BaseRate() {}

    public BaseRate(UUID tenantId, UUID productVersionId, int ageFrom, int ageTo, String sex,
                    String smokerStatus, BigDecimal ratePerMille) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.ageFrom = ageFrom;
        this.ageTo = ageTo;
        this.sex = sex;
        this.smokerStatus = smokerStatus;
        this.ratePerMille = ratePerMille;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public int getAgeFrom() { return ageFrom; }
    public int getAgeTo() { return ageTo; }
    public String getSex() { return sex; }
    public String getSmokerStatus() { return smokerStatus; }
    public BigDecimal getRatePerMille() { return ratePerMille; }
}
