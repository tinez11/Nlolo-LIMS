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

    public ProductDefinition(UUID tenantId, String productCode, String productName, String category, String defaultCurrency, String createdBy) {
        this.tenantId = tenantId;
        this.productCode = productCode;
        this.productName = productName;
        this.category = category;
        this.defaultCurrency = defaultCurrency;
        this.createdBy = createdBy;
    }

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
