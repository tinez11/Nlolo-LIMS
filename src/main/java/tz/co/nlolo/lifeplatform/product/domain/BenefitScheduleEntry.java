package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

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

    @Column(name = "calculation_method", nullable = false)
    private String calculationMethod;

    protected BenefitScheduleEntry() {}

    public BenefitScheduleEntry(UUID tenantId, UUID productVersionId, String benefitType, String calculationMethod) {
        this.tenantId = tenantId;
        this.productVersionId = productVersionId;
        this.benefitType = benefitType;
        this.calculationMethod = calculationMethod;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public String getBenefitType() { return benefitType; }
    public String getCalculationMethod() { return calculationMethod; }
}
