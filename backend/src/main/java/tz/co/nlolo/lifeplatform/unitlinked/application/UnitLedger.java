package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FrozenPolicy;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PendingOrder;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FrozenPolicyRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PendingOrderRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The unit ledger's one writer of priced movements (spec §5). An order becomes exactly one entry at exactly the
 * price that priced it; holdings are the sum of entries, never stored. Called inside a pricing run's transaction.
 */
@Component
class UnitLedger {

    static final String SYSTEM = "system:unitlinked";

    private final UnitEntryRepository entries;
    private final PendingOrderRepository orders;
    private final FrozenPolicyRepository frozen;
    // Looked up when an order is priced, not at construction: a listener that also uses the ledger (Exits) would
    // otherwise be a constructor cycle, which Spring refuses.
    private final org.springframework.beans.factory.ObjectProvider<UnitsPricedListener> listeners;
    private final Clock clock;

    UnitLedger(UnitEntryRepository entries, PendingOrderRepository orders, FrozenPolicyRepository frozen,
               org.springframework.beans.factory.ObjectProvider<UnitsPricedListener> listeners, @Qualifier("unitLinkedClock") Clock clock) {
        this.entries = entries;
        this.orders = orders;
        this.frozen = frozen;
        this.listeners = listeners;
        this.clock = clock;
    }

    /** Units held per fund (zero holdings included), in a stable order. */
    Map<UUID, BigDecimal> holdings(String policyNumber) {
        Map<UUID, BigDecimal> held = new LinkedHashMap<>();
        entries.holdings(TenantContext.get(), policyNumber).stream()
            .sorted(java.util.Comparator.comparing(r -> r[0].toString()))
            .forEach(r -> held.put((UUID) r[0], (BigDecimal) r[1]));
        return held;
    }

    boolean isFrozen(String policyNumber) {
        return frozen.existsByTenantIdAndPolicyNumber(TenantContext.get(), policyNumber);
    }

    void freeze(String policyNumber, FrozenPolicy.Reason reason, String sourceRef) {
        if (!isFrozen(policyNumber)) {
            frozen.save(new FrozenPolicy(TenantContext.get(), policyNumber, reason, sourceRef, clock.instant()));
        }
    }

    void unfreeze(String policyNumber) {
        frozen.findByTenantIdAndPolicyNumber(TenantContext.get(), policyNumber).ifPresent(frozen::delete);
    }

    /**
     * Prices one order at {@code price} and writes its entry. A BUY of money M buys {@code M / price} units,
     * truncated; a money SELL cancels enough units to cover it, never more than held; a SELL ALL sells the holding.
     * Returns the money the entry moved (negative for a sale), or zero when there was nothing to sell.
     */
    BigDecimal execute(PendingOrder order, FundPrice price) {
        UnitEntry entry = null;
        if (order.getSide() == PendingOrder.Side.BUY) {
            BigDecimal units = UnitArithmetic.unitsBought(order.getAmount(), price.getPrice());
            UnitEntry.Type type = order.getPurpose() == PendingOrder.Purpose.REINVESTMENT
                ? UnitEntry.Type.REINVESTMENT : UnitEntry.Type.ALLOCATION;
            entry = UnitEntry.priced(order, type, units, price, order.getAmount(), SYSTEM, clock.instant());
        } else {
            BigDecimal held = entries.sumUnits(order.getTenantId(), order.getPolicyNumber(), order.getFundId());
            if (held.signum() > 0) {
                BigDecimal units;
                BigDecimal money;
                if (order.isSellAll()) {
                    units = held;
                    money = UnitArithmetic.proceeds(units, price.getPrice());
                } else {
                    units = UnitArithmetic.unitsToSell(order.getAmount(), price.getPrice(), held);
                    // Covered in full: the charge takes exactly its amount. Capped at the holding: only what the
                    // units raise -- the rest is written off by the charge run, never billed (spec §6).
                    money = units.compareTo(held) < 0 ? order.getAmount()
                        : UnitArithmetic.proceeds(units, price.getPrice()).min(order.getAmount());
                }
                entry = UnitEntry.priced(order, saleType(order), units.negate(), price, money.negate(), SYSTEM, clock.instant());
            }
        }
        if (entry != null) {
            entries.save(entry);
        }
        order.markPriced(price.getPriceId(), clock.instant());
        orders.save(order);
        final UnitEntry priced = entry;
        listeners.orderedStream().forEach(listener -> listener.afterPriced(order, priced));
        return entry == null ? BigDecimal.ZERO : entry.getAmount();
    }

    private static UnitEntry.Type saleType(PendingOrder order) {
        return switch (order.getPurpose()) {
            case CHARGES -> UnitEntry.Type.valueOf(order.getEntryType());
            case DEATH -> UnitEntry.Type.DEATH_SALE;
            case SURRENDER -> UnitEntry.Type.SURRENDER_SALE;
            case MATURITY -> UnitEntry.Type.MATURITY_SALE;
            case LAPSE -> UnitEntry.Type.LAPSE_SALE;
            case FREE_LOOK -> UnitEntry.Type.FREE_LOOK_SALE;
            case WITHDRAWAL -> UnitEntry.Type.WITHDRAWAL_SALE;
            case ALLOCATION, REINVESTMENT -> throw new IllegalStateException("A " + order.getPurpose() + " order buys units");
        };
    }
}
