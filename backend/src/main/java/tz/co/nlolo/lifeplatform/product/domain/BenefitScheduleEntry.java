package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "benefit_schedule", schema = "product")
public class BenefitScheduleEntry {

    @Id
    @UuidGenerator
    @Column(name = "benefit_schedule_id")
    private UUID benefitScheduleId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_version_id", nullable = false)
    private UUID productVersionId;

    @Column(name = "benefit_type", nullable = false)
    private String benefitType;

    /**
     * One of {@code BenefitCalculationMethod} as of V13, enforced by
     * {@code benefit_schedule_calculation_method_known}.
     *
     * <p>Was unconstrained free text, and held the typed prose {@code untill death} on two of the
     * three rows that existed — which nothing noticed, because no computation had ever read it.
     */
    @Column(name = "calculation_method", nullable = false)
    private String calculationMethod;

    // What this benefit pays (V13). Exactly one is set, or neither for SUM_ASSURED --
    // benefit_schedule_amount_shape enforces that at the database.
    @Column(name = "benefit_percent")
    private BigDecimal benefitPercent;

    @Column(name = "flat_amount")
    private BigDecimal flatAmount;

    protected BenefitScheduleEntry() {}

    public BenefitScheduleEntry(UUID tenantId, UUID productVersionId, String benefitType,
                                 String calculationMethod, BigDecimal benefitPercent, BigDecimal flatAmount) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.benefitType = benefitType;
        this.calculationMethod = calculationMethod;
        this.benefitPercent = benefitPercent;
        this.flatAmount = flatAmount;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getBenefitType() { return benefitType; }
    public String getCalculationMethod() { return calculationMethod; }
    public BigDecimal getBenefitPercent() { return benefitPercent; }
    public BigDecimal getFlatAmount() { return flatAmount; }
}
