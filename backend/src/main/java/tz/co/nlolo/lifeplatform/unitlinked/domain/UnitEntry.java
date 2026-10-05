package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One movement on the unit ledger (unitlinked V2): append-only, enforced by the database. Units are signed (+ a
 * buy, − a sale); money is signed the same way. Money-only entries -- the allocation charge, a refunded charge, a
 * written-off shortfall -- carry no fund, units or price. No setters: an entry is never changed, only reversed.
 */
@Entity
@Table(name = "unit_entry", schema = "unitlinked")
public class UnitEntry {

    public enum Type {
        ALLOCATION, ALLOCATION_CHARGE, POLICY_FEE, COST_OF_INSURANCE, DEATH_SALE, SURRENDER_SALE, MATURITY_SALE,
        LAPSE_SALE, FREE_LOOK_SALE, CHARGE_REFUND, REINVESTMENT, PRICE_CORRECTION, WRITE_OFF;

        public boolean moneyOnly() {
            return this == ALLOCATION_CHARGE || this == CHARGE_REFUND || this == WRITE_OFF;
        }
    }

    @Id @Column(name = "entry_id") private UUID entryId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "fund_id") private UUID fundId;
    @Column(name = "entry_type", nullable = false) private String entryType;
    @Column(name = "units", nullable = false) private BigDecimal units;
    @Column(name = "price") private BigDecimal price;
    @Column(name = "price_id") private UUID priceId;
    @Column(name = "amount", nullable = false) private BigDecimal amount;
    @Column(name = "valuation_date", nullable = false) private LocalDate valuationDate;
    @Column(name = "bound_date") private LocalDate boundDate;
    @Column(name = "order_id") private UUID orderId;
    @Column(name = "source_type", nullable = false) private String sourceType;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Column(name = "reverses_entry_id") private UUID reversesEntryId;
    @Column(name = "created_by", nullable = false) private String createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected UnitEntry() {}

    private UnitEntry(UUID tenantId, String policyNumber, UUID fundId, Type type, BigDecimal units, BigDecimal price,
                      UUID priceId, BigDecimal amount, LocalDate valuationDate, LocalDate boundDate, UUID orderId,
                      String sourceType, String sourceRef, UUID reversesEntryId, String createdBy, Instant createdAt) {
        this.entryId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.fundId = fundId;
        this.entryType = type.name();
        this.units = units;
        this.price = price;
        this.priceId = priceId;
        this.amount = amount;
        this.valuationDate = valuationDate;
        this.boundDate = boundDate;
        this.orderId = orderId;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.reversesEntryId = reversesEntryId;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    /** Units bought or sold at an approved price, from a priced order. */
    public static UnitEntry priced(PendingOrder order, Type type, BigDecimal units, FundPrice price, BigDecimal amount,
                                   String createdBy, Instant now) {
        return new UnitEntry(order.getTenantId(), order.getPolicyNumber(), order.getFundId(), type, units, price.getPrice(),
            price.getPriceId(), amount, price.getValuationDate(), order.getBoundDate(), order.getOrderId(),
            order.getSourceType(), order.getSourceRef(), null, createdBy, now);
    }

    /** Money with no units: the allocation charge, a refunded charge, a written-off shortfall. */
    public static UnitEntry money(UUID tenantId, String policyNumber, Type type, BigDecimal amount, LocalDate valuationDate,
                                  String sourceType, String sourceRef, UUID reversesEntryId, String createdBy, Instant now) {
        if (!type.moneyOnly()) {
            throw new IllegalArgumentException(type + " moves units; it is not a money-only entry");
        }
        return new UnitEntry(tenantId, policyNumber, null, type, BigDecimal.ZERO.setScale(6), null, null, amount,
            valuationDate, null, null, sourceType, sourceRef, reversesEntryId, createdBy, now);
    }

    /** The exact opposite of {@code original}, as a PRICE_CORRECTION keyed on its own source (Task 5). */
    public static UnitEntry reversal(UnitEntry original, String sourceRef, String createdBy, Instant now) {
        return new UnitEntry(original.tenantId, original.policyNumber, original.fundId, Type.PRICE_CORRECTION,
            original.units.negate(), original.price, original.priceId, original.amount.negate(), original.valuationDate,
            original.boundDate, original.orderId, original.sourceType, sourceRef, original.entryId, createdBy, now);
    }

    /** {@code original} entered again at a corrected price: same type, order and dates, its own source (Task 5). */
    public static UnitEntry reEntry(UnitEntry original, BigDecimal units, FundPrice corrected, BigDecimal amount,
                                    String sourceRef, String createdBy, Instant now) {
        return new UnitEntry(original.tenantId, original.policyNumber, original.fundId, original.getType(), units,
            corrected.getPrice(), corrected.getPriceId(), amount, original.valuationDate, original.boundDate,
            original.orderId, original.sourceType, sourceRef, original.entryId, createdBy, now);
    }

    public UUID getEntryId() { return entryId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getFundId() { return fundId; }
    public Type getType() { return Type.valueOf(entryType); }
    public BigDecimal getUnits() { return units; }
    public BigDecimal getPrice() { return price; }
    public UUID getPriceId() { return priceId; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getValuationDate() { return valuationDate; }
    public LocalDate getBoundDate() { return boundDate; }
    public UUID getOrderId() { return orderId; }
    public String getSourceType() { return sourceType; }
    public String getSourceRef() { return sourceRef; }
    public UUID getReversesEntryId() { return reversesEntryId; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
