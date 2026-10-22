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

/**
 * An account charge (product V32): taken by the product versions that pick it (V34) and by the policies issued on a choice
 * of their own, over a range of policy years. Never deleted or changed, only withdrawn from the choice.
 */
@Entity
@Table(name = "account_charge", schema = "product")
public class AccountCharge {

    public static final Set<String> WHEN = Set.of("DEPOSIT", "PREMIUM", "TRANSFER_IN", "WITHDRAWAL", "MONTHLY", "YEARLY",
        "OPENING", "MATURITY");
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

    @Column(name = "from_policy_year", nullable = false)
    private int fromPolicyYear = 1;

    /** Null: every year from {@code fromPolicyYear} on. */
    @Column(name = "to_policy_year")
    private Integer toPolicyYear;

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
        this(tenantId, name, description, when, amountType, amount, currency, 1, null, createdBy);
    }

    public AccountCharge(UUID tenantId, String name, String description, String when, String amountType,
                         BigDecimal amount, String currency, int fromPolicyYear, Integer toPolicyYear, String createdBy) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name the charge");
        }
        if (when == null || !WHEN.contains(when)) {
            throw new IllegalArgumentException("Say when the charge is taken: on each deposit, each premium, each transfer"
                + " in, each withdrawal, monthly, yearly, once at opening or at maturity");
        }
        if (fromPolicyYear < 1) {
            throw new IllegalArgumentException("A charge starts in policy year 1 or later");
        }
        if (toPolicyYear != null && toPolicyYear < fromPolicyYear) {
            throw new IllegalArgumentException("A charge cannot end (year " + toPolicyYear + ") before it starts (year "
                + fromPolicyYear + ")");
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
        this.fromPolicyYear = fromPolicyYear;
        this.toPolicyYear = toPolicyYear;
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
    public int getFromPolicyYear() { return fromPolicyYear; }
    public Integer getToPolicyYear() { return toPolicyYear; }
    public boolean isActive() { return active; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
