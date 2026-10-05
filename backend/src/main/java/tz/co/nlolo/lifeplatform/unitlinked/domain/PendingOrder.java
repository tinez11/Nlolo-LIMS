package tz.co.nlolo.lifeplatform.unitlinked.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * Money or units waiting for a forward price (unitlinked V2; spec §5). Bound once, at creation, to the valuation
 * date its arrival and the fund's cut-off fix, and priced only by the first APPROVED price dated on or after it.
 */
@Entity
@Table(name = "pending_order", schema = "unitlinked")
public class PendingOrder {

    public enum Side { BUY, SELL }

    public enum Purpose { ALLOCATION, CHARGES, DEATH, SURRENDER, MATURITY, LAPSE, FREE_LOOK, REINVESTMENT, WITHDRAWAL }

    @Id @Column(name = "order_id") private UUID orderId;
    @Column(name = "tenant_id", nullable = false) private UUID tenantId;
    @Column(name = "policy_number", nullable = false) private String policyNumber;
    @Column(name = "fund_id", nullable = false) private UUID fundId;
    @Column(name = "side", nullable = false) private String side;
    @Column(name = "amount") private BigDecimal amount;
    @Column(name = "sell_all", nullable = false) private boolean sellAll;
    @Column(name = "purpose", nullable = false) private String purpose;
    @Column(name = "entry_type") private String entryType;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    @Column(name = "bound_date", nullable = false) private LocalDate boundDate;
    @Column(name = "source_type", nullable = false) private String sourceType;
    @Column(name = "source_ref", nullable = false) private String sourceRef;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "priced_by_price_id") private UUID pricedByPriceId;
    @Column(name = "priced_at") private Instant pricedAt;
    @Version @Column(name = "version", nullable = false) private long version;

    protected PendingOrder() {}

    private PendingOrder(UUID tenantId, String policyNumber, UUID fundId, Side side, BigDecimal amount, boolean sellAll,
                         Purpose purpose, String entryType, Instant receivedAt, LocalTime cutOff, String sourceType,
                         String sourceRef) {
        this.orderId = UUID.randomUUID();
        this.tenantId = tenantId;
        this.policyNumber = policyNumber;
        this.fundId = fundId;
        this.side = side.name();
        this.amount = amount;
        this.sellAll = sellAll;
        this.purpose = purpose.name();
        this.entryType = entryType;
        this.receivedAt = receivedAt;
        // The binding, fixed here and never recomputed.
        this.boundDate = BindingRule.boundDate(receivedAt, cutOff);
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.status = "WAITING";
    }

    public static PendingOrder buy(UUID tenantId, String policyNumber, UUID fundId, BigDecimal amount, Purpose purpose,
                                   Instant receivedAt, LocalTime cutOff, String sourceType, String sourceRef) {
        requirePositive(amount);
        return new PendingOrder(tenantId, policyNumber, fundId, Side.BUY, amount, false, purpose, null, receivedAt, cutOff,
            sourceType, sourceRef);
    }

    /** A charge sold for money: the fee or the cost of insurance, named by {@code entryType}. */
    public static PendingOrder sellCharge(UUID tenantId, String policyNumber, UUID fundId, BigDecimal amount, String entryType,
                                          Instant receivedAt, LocalTime cutOff, String sourceType, String sourceRef) {
        requirePositive(amount);
        return new PendingOrder(tenantId, policyNumber, fundId, Side.SELL, amount, false, Purpose.CHARGES, entryType,
            receivedAt, cutOff, sourceType, sourceRef);
    }

    /** Every unit of the fund the policy holds when it is priced: an exit (death, surrender, maturity, lapse, free-look). */
    public static PendingOrder sellAll(UUID tenantId, String policyNumber, UUID fundId, Purpose purpose, Instant receivedAt,
                                       LocalTime cutOff, String sourceType, String sourceRef) {
        return new PendingOrder(tenantId, policyNumber, fundId, Side.SELL, null, true, purpose, null, receivedAt, cutOff,
            sourceType, sourceRef);
    }

    /** Money sold from one fund for a withdrawal (U2): as many units as cover it, never more than held. */
    public static PendingOrder sellAmount(UUID tenantId, String policyNumber, UUID fundId, BigDecimal amount, Purpose purpose,
                                          Instant receivedAt, LocalTime cutOff, String sourceType, String sourceRef) {
        requirePositive(amount);
        return new PendingOrder(tenantId, policyNumber, fundId, Side.SELL, amount, false, purpose, null, receivedAt, cutOff,
            sourceType, sourceRef);
    }

    public void markPriced(UUID priceId, Instant at) {
        if (!"WAITING".equals(status)) {
            throw new UnitLinkedStateException("Order " + orderId + " is " + status + "; only a waiting order is priced");
        }
        this.status = "PRICED";
        this.pricedByPriceId = priceId;
        this.pricedAt = at;
    }

    public void cancel() {
        if (!"WAITING".equals(status)) {
            throw new UnitLinkedStateException("Order " + orderId + " is " + status + "; only a waiting order is cancelled");
        }
        this.status = "CANCELLED";
    }

    private static void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("An order's amount must be greater than zero");
        }
    }

    public UUID getOrderId() { return orderId; }
    public UUID getTenantId() { return tenantId; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getFundId() { return fundId; }
    public Side getSide() { return Side.valueOf(side); }
    public BigDecimal getAmount() { return amount; }
    public boolean isSellAll() { return sellAll; }
    public Purpose getPurpose() { return Purpose.valueOf(purpose); }
    public String getEntryType() { return entryType; }
    public Instant getReceivedAt() { return receivedAt; }
    public LocalDate getBoundDate() { return boundDate; }
    public String getSourceType() { return sourceType; }
    public String getSourceRef() { return sourceRef; }
    public String getStatus() { return status; }
    public UUID getPricedByPriceId() { return pricedByPriceId; }
    public Instant getPricedAt() { return pricedAt; }
    public boolean isWaiting() { return "WAITING".equals(status); }
}
