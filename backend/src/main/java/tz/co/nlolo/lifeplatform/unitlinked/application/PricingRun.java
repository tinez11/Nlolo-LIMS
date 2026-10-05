package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundLiability;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PendingOrder;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundLiabilityRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PendingOrderRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What an approved price does (spec §5, §8), inside the approval's transaction:
 *
 * <ol>
 *   <li>every order waiting on this fund for a date up to the price's prices at it -- an order whose own date had
 *       no price (a holiday) is swept to the first approved price after it, never to an earlier one, never to zero;
 *   <li>the fund's liability is trued up to exactly {@code units in issue × price}, rounded once, against what the
 *       ledger carried plus this run's own movements. That one posting carries the price movement on the units
 *       already held and every sub-cent residue of the run's entries, so 2150 never drifts (plan D1).
 * </ol>
 */
@Component
class PricingRun {

    private final PendingOrderRepository orders;
    private final UnitEntryRepository entries;
    private final FundLiabilityRepository liabilities;
    private final UnitLedger ledger;
    private final ApplicationEventPublisher events;
    // Looked up per run, not at construction: Switches reaches the ledger, which reaches the pricing run's listeners.
    private final org.springframework.beans.factory.ObjectProvider<Switches> switches;

    PricingRun(PendingOrderRepository orders, UnitEntryRepository entries, FundLiabilityRepository liabilities,
               UnitLedger ledger, ApplicationEventPublisher events,
               org.springframework.beans.factory.ObjectProvider<Switches> switches) {
        this.switches = switches;
        this.orders = orders;
        this.entries = entries;
        this.liabilities = liabilities;
        this.ledger = ledger;
        this.events = events;
    }

    void onApproved(Fund fund, FundPrice price) {
        UUID tenantId = TenantContext.get();
        FundLiability liability = liabilities.lockFor(tenantId, fund.getFundId())
            .orElseGet(() -> new FundLiability(tenantId, fund.getFundId()));
        BigDecimal carried = liability.getCarried();
        BigDecimal moved = BigDecimal.ZERO;
        for (PendingOrder order : orders.findWaiting(tenantId, fund.getFundId(), price.getValuationDate())) {
            moved = moved.add(ledger.execute(order, price));
        }
        trueUp(fund, price, liability, carried.add(moved));
        // Last (U2, spec §2): a waiting switch moving this fund executes once all its funds are priced for one date.
        // Its legs move each fund's carried liability themselves, so the next true-up measures from them.
        switches.getObject().onPriceApproved(fund, price);
    }

    /**
     * Sets the fund's carried liability to {@code units in issue × price} and publishes the difference from
     * {@code before} as the revaluation. Also used by a price correction (Task 5).
     */
    void trueUp(Fund fund, FundPrice price, FundLiability liability, BigDecimal before) {
        UUID tenantId = TenantContext.get();
        BigDecimal inIssue = entries.unitsInIssue(tenantId, fund.getFundId());
        BigDecimal target = inIssue.multiply(price.getPrice()).setScale(2, RoundingMode.HALF_EVEN);
        BigDecimal delta = target.subtract(before);
        liability.carry(target, price.getPriceId(), inIssue);
        liabilities.save(liability);
        if (delta.signum() != 0) {
            Map<String, Object> payload = new HashMap<>();
            payload.put("priceId", price.getPriceId().toString());
            payload.put("fundId", fund.getFundId().toString());
            payload.put("fundCode", fund.getCode());
            payload.put("valuationDate", price.getValuationDate().toString());
            payload.put("unitsInIssue", inIssue.toPlainString());
            payload.put("price", price.getPrice().toPlainString());
            payload.put("carriedBefore", before.toPlainString());
            payload.put("carried", target.toPlainString());
            payload.put("delta", Map.of("amount", delta.toPlainString(), "currencyCode", fund.getCurrency()));
            events.publishEvent(DomainEventEnvelope.of("unitlinked.FundRevalued", tenantId, payload));
        }
    }
}
