package tz.co.nlolo.lifeplatform.regreporting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps {@code regreporting.premium_movement} -- gross premium collected for one
 * {@code (tenant, period, product)} (V2 section 6). The measure defaults to zero and is
 * incremented by {@link #applyCollected(BigDecimal)}, matching the DB's
 * {@code premium_movement_non_negative} CHECK.
 */
@Entity
@Table(name = "premium_movement", schema = "regreporting")
@IdClass(PremiumMovementId.class)
public class PremiumMovement {

    @Id
    @Column(name = "tenant_id")
    private UUID tenantId;

    @Id
    @Column(name = "period")
    private String period;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "collected_amount", nullable = false)
    private BigDecimal collectedAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false)
    private String currency;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /**
     * Optimistic lock (regreporting/V3, M10 final review C1) -- see {@link PolicyMovement}'s
     * {@code version} javadoc for the read-modify-write race this closes. {@code
     * BillingEventListener} maintains this row the same way and retries the same bounded 3 attempts.
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected PremiumMovement() {}

    /** Creates the row for {@code (tenantId, period, productId)} with the measure at zero. */
    public PremiumMovement(UUID tenantId, String period, UUID productId, String currency) {
        this.tenantId = tenantId;
        this.period = period;
        this.productId = productId;
        this.currency = currency;
    }

    public void applyCollected(BigDecimal amount) {
        this.collectedAmount = this.collectedAmount.add(amount);
        this.updatedAt = Instant.now();
    }

    public UUID getTenantId() { return tenantId; }
    public String getPeriod() { return period; }
    public UUID getProductId() { return productId; }
    public BigDecimal getCollectedAmount() { return collectedAmount; }
    public String getCurrency() { return currency; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
