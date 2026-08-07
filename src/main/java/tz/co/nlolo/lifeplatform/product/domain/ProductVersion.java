package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "product_version", schema = "product")
public class ProductVersion {

    @Id
    @UuidGenerator
    @Column(name = "product_version_id")
    private UUID productVersionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "effective_date", nullable = false)
    private LocalDate effectiveDate;

    @Column(name = "retirement_date")
    private LocalDate retirementDate;

    @Column(name = "is_active_for_new_business", nullable = false)
    private boolean activeForNewBusiness = true;

    @Column(name = "grace_period_days", nullable = false)
    private int gracePeriodDays;

    @Column(name = "max_loan_to_value_percent")
    private BigDecimal maxLoanToValuePercent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    protected ProductVersion() {}

    public ProductVersion(UUID tenantId, UUID productId, LocalDate effectiveDate, LocalDate retirementDate,
                           int gracePeriodDays, BigDecimal maxLoanToValuePercent, String createdBy) {
        this.tenantId = tenantId;
        this.productId = productId;
        this.effectiveDate = effectiveDate;
        this.retirementDate = retirementDate;
        this.gracePeriodDays = gracePeriodDays;
        this.maxLoanToValuePercent = maxLoanToValuePercent;
        this.createdBy = createdBy;
    }

    public UUID getProductVersionId() { return productVersionId; }
    public UUID getTenantId() { return tenantId; }
    public UUID getProductId() { return productId; }
    public LocalDate getEffectiveDate() { return effectiveDate; }
    public LocalDate getRetirementDate() { return retirementDate; }
    public boolean isActiveForNewBusiness() { return activeForNewBusiness; }
    public int getGracePeriodDays() { return gracePeriodDays; }
    public BigDecimal getMaxLoanToValuePercent() { return maxLoanToValuePercent; }
}
