package tz.co.nlolo.lifeplatform.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** An account charge staff choose per savings policy (product V32). Never deleted, only withdrawn from the choice. */
@Entity
@Table(name = "account_charge", schema = "product")
public class AccountCharge {

    public static final Set<String> WHEN = Set.of("DEPOSIT", "WITHDRAWAL", "MONTHLY", "YEARLY", "OPENING", "MATURITY");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Id
    @Column(name = "charge_id")
    private UUID chargeId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "charge_when", nullable = false)
    private String when;

    @Column(name = "amount_type", nullable = false)
    private String amountType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private Long version;

    protected AccountCharge() {}

    public AccountCharge(UUID tenantId, String name, String description, String when, String amountType,
                         BigDecimal amount, String currency, String createdBy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name the charge");
        }
        if (when == null || !WHEN.contains(when)) {
            throw new IllegalArgumentException("Say when the charge is taken: on each deposit, on each withdrawal, monthly,"
                + " yearly, once at opening or at maturity");
        }
        if (!"FLAT".equals(amountType) && !"PERCENT".equals(amountType)) {
            throw new IllegalArgumentException("A charge is a flat amount or a percentage");
        }
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("The charge must be more than zero");
        }
        if ("PERCENT".equals(amountType) && amount.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("A percentage charge cannot be more than 100%");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalArgumentException("Give the currency, e.g. TZS");
        }
        this.chargeId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name.trim();
        this.description = description == null || description.isBlank() ? null : description.trim();
        this.when = when;
        this.amountType = amountType;
        this.amount = amount;
        this.currency = currency;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public void setActive(boolean active) { this.active = active; }

    public UUID getChargeId() { return chargeId; }
    public UUID getTenantId() { return tenantId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getWhen() { return when; }
    public String getAmountType() { return amountType; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public boolean isActive() { return active; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
