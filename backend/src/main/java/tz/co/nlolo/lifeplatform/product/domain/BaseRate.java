package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One cell of a product version's base rate table: the annual rate per TZS 1,000
 * of sum assured for a given (age band, sex, smoker status).
 *
 * The base a premium is computed FROM. {@link RatingFactor}'s multipliers apply
 * on top of it for occupation class and sum-assured band, so those dimensions
 * keep pricing independently.
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

    @Column(name = "age_band", nullable = false)
    private String ageBand;

    @Column(nullable = false)
    private String sex;

    @Column(name = "smoker_status", nullable = false)
    private String smokerStatus;

    @Column(name = "rate_per_mille", nullable = false)
    private BigDecimal ratePerMille;

    protected BaseRate() {}

    public BaseRate(UUID tenantId, UUID productVersionId, String ageBand, String sex,
                    String smokerStatus, BigDecimal ratePerMille) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.ageBand = ageBand;
        this.sex = sex;
        this.smokerStatus = smokerStatus;
        this.ratePerMille = ratePerMille;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getAgeBand() { return ageBand; }
    public String getSex() { return sex; }
    public String getSmokerStatus() { return smokerStatus; }
    public BigDecimal getRatePerMille() { return ratePerMille; }
}
