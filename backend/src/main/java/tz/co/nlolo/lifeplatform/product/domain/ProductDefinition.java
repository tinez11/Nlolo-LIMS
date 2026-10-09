package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_definition", schema = "product")
public class ProductDefinition {

    @Id
    @UuidGenerator
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String status = "DRAFT";

    @Column(name = "default_currency", nullable = false)
    private String defaultCurrency;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private String createdBy;

    protected ProductDefinition() {}

    /**
     * The IFRS 17 portfolio (spec §6): contracts subject to similar risks and managed together. With the cohort and the
     * version's expected profitability it decides the group of contracts a policy joins at issue.
     */
    @Column(name = "portfolio_code", nullable = false)
    private String portfolioCode;

    /** Offered to customers in the portal (V31). */
    @Column(name = "available_online", nullable = false)
    private boolean availableOnline;

    /** What the product is for, in a sentence a customer reads (V31). Required while offered online. */
    @Column(name = "online_summary")
    private String onlineSummary;

    /** Its key benefits, one per line (V31). */
    @Column(name = "online_benefits")
    private String onlineBenefits;

    public void describeOnline(boolean available, String summary, java.util.List<String> benefits) {
        String trimmed = summary == null ? null : summary.trim();
        if (available && (trimmed == null || trimmed.isEmpty())) {
            throw new IllegalArgumentException("Say what the product is for before offering it online");
        }
        this.availableOnline = available;
        this.onlineSummary = trimmed == null || trimmed.isEmpty() ? null : trimmed;
        String lines = benefits == null ? "" : benefits.stream().map(String::trim).filter(b -> !b.isEmpty())
            .collect(java.util.stream.Collectors.joining("\n"));
        this.onlineBenefits = lines.isEmpty() ? null : lines;
    }

    public boolean isAvailableOnline() { return availableOnline; }
    public String getOnlineSummary() { return onlineSummary; }
    public java.util.List<String> getOnlineBenefits() {
        return onlineBenefits == null ? java.util.List.of() : java.util.List.of(onlineBenefits.split("\n"));
    }

    public ProductDefinition(UUID tenantId, String productCode, String productName, String category, String defaultCurrency, String createdBy) {
        this(tenantId, productCode, productName, category, null, defaultCurrency, createdBy);
    }

    public ProductDefinition(UUID tenantId, String productCode, String productName, String category, String portfolioCode,
                             String defaultCurrency, String createdBy) {
        this.tenantId = tenantId;
        this.productCode = productCode;
        this.productName = productName;
        this.category = category;
        this.portfolioCode = portfolioCode != null ? portfolioCode
            : tz.co.nlolo.lifeplatform.product.api.PortfolioCode.defaultFor(
                tz.co.nlolo.lifeplatform.product.api.ProductCategory.valueOf(category)).name();
        this.defaultCurrency = defaultCurrency;
        this.createdBy = createdBy;
    }

    public String getPortfolioCode() { return portfolioCode; }

    public UUID getProductId() { return productId; }
    public UUID getTenantId() { return tenantId; }
    public String getProductCode() { return productCode; }
    public String getProductName() { return productName; }
    public String getCategory() { return category; }
    public String getStatus() { return status; }
    public String getDefaultCurrency() { return defaultCurrency; }
    /**
     * A product becomes ACTIVE by having a version published against it, and never any other way.
     *
     * <p>This was {@code activateWithMeasurementModel(String)}, which also SET the model — and
     * because {@code publishVersion} called it unconditionally, republishing rewrote the
     * measurement basis of every contract already issued under the product.
     *
     * <p>The Deliverable 3 invariant it used to assert — a measurement model is required before a
     * product may leave DRAFT — is now structural rather than checked. The model lives on
     * {@code product_version}, where it is NOT NULL, so a version cannot exist without one and
     * only publishing a version activates a product (V10).
     */
    public void activate() {
        this.status = "ACTIVE";
    }
}
