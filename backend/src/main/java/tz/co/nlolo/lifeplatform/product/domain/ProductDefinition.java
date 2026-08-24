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

    @Column(name = "ifrs_measurement_model")
    private String ifrsMeasurementModel;

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
    public String getIfrsMeasurementModel() { return ifrsMeasurementModel; }

    public void activateWithMeasurementModel(String ifrsMeasurementModel) {
        // Deliverable 3 invariant: ifrsMeasurementModel mandatory before leaving DRAFT.
        // Enforced here (aggregate boundary) rather than only at the DB CHECK level so
        // the failure surfaces as a readable 422 at the API layer, not a raw constraint error.
        if (ifrsMeasurementModel == null || ifrsMeasurementModel.isBlank()) {
            throw new IllegalArgumentException("ifrsMeasurementModel is required before a product can become ACTIVE");
        }
        this.ifrsMeasurementModel = ifrsMeasurementModel;
        this.status = "ACTIVE";
    }
}
