package tz.co.nlolo.lifeplatform.policy.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "coverage", schema = "policy")
public class Coverage {

    @Id
    @Column(name = "coverage_id")
    private UUID coverageId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "policy_number", nullable = false)
    private String policyNumber;

    @Column(name = "benefit_type", nullable = false)
    private String benefitType;

    @Column(name = "sum_assured_amount", nullable = false)
    private BigDecimal sumAssuredAmount;

    @Column(name = "sum_assured_currency", nullable = false)
    private String sumAssuredCurrency = "TZS";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Coverage() {}

    public Coverage(UUID tenantId, String policyNumber, String benefitType, BigDecimal sumAssuredAmount, String sumAssuredCurrency) {
        this.coverageId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.benefitType = benefitType;
        this.sumAssuredAmount = sumAssuredAmount;
        this.sumAssuredCurrency = sumAssuredCurrency;
    }

    public UUID getCoverageId() { return coverageId; }
    public String getPolicyNumber() { return policyNumber; }
    public String getBenefitType() { return benefitType; }
    public BigDecimal getSumAssuredAmount() { return sumAssuredAmount; }
    public String getSumAssuredCurrency() { return sumAssuredCurrency; }
    public boolean isActive() { return active; }
}
