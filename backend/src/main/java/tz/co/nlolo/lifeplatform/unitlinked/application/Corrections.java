package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.ExitState;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundLiability;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PriceCorrectionAdjustment;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.ExitStateRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundLiabilityRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundPriceRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PriceCorrectionAdjustmentRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * A two-person price correction (spec §3), inside the approval's transaction. The wrong price becomes SUPERSEDED,
 * the right one APPROVED, and every movement priced at the wrong one is reversed and entered again at the right one
 * -- new entries, nothing overwritten. Movements on later dates keep their own prices. Where a corrected movement
 * funded a payout already PAID, the difference becomes an adjustment on a staff queue; where its exit is priced but
 * not yet paid, the exit simply carries the corrected proceeds.
 *
 * <p>If the corrected history would leave a policy short of units -- a correction that buys fewer units than were
 * later sold -- the whole correction is refused and nothing changes: correct the later prices first.
 */
@Component
class Corrections {

    private final FundPriceRepository prices;
    private final UnitEntryRepository entries;
    private final ExitStateRepository exits;
    private final PriceCorrectionAdjustmentRepository adjustments;
    private final FundLiabilityRepository liabilities;
    private final PricingRun pricingRun;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    Corrections(FundPriceRepository prices, UnitEntryRepository entries, ExitStateRepository exits,
                PriceCorrectionAdjustmentRepository adjustments, FundLiabilityRepository liabilities, PricingRun pricingRun,
                ApplicationEventPublisher events, @Qualifier("unitLinkedClock") Clock clock) {
        this.prices = prices;
        this.entries = entries;
        this.exits = exits;
        this.adjustments = adjustments;
        this.liabilities = liabilities;
        this.pricingRun = pricingRun;
        this.events = events;
        this.clock = clock;
    }

    private record Redo(UnitEntry original, BigDecimal units, BigDecimal amount) {}

    void apply(Fund fund, FundPrice wrong, FundPrice corrected, String approvedBy) {
        UUID tenantId = TenantContext.get();
        // The wrong price first, so the one-approved-per-date index lets the corrected one in.
        wrong.supersede();
        prices.saveAndFlush(wrong);
        corrected.approve(approvedBy, clock.instant(), fund.getCode(), fund.getCutOffTime());
        prices.saveAndFlush(corrected);

        List<UnitEntry> priced = entries.findByTenantIdAndPriceId(tenantId, wrong.getPriceId()).stream()
            .filter(e -> e.getType() != UnitEntry.Type.PRICE_CORRECTION).toList();
        List<Redo> redos = priced.stream().map(e -> redo(e, corrected.getPrice())).toList();
        refuseAnyShortfall(tenantId, redos);

        FundLiability liability = liabilities.lockFor(tenantId, fund.getFundId())
            .orElseThrow(() -> new IllegalStateException("Fund " + fund.getCode() + " has prices but no carried liability"));
        BigDecimal moved = BigDecimal.ZERO;
        List<Map<String, Object>> movements = new ArrayList<>();
        // Every entry that adds units before any that removes them, so no holding dips below zero on the way to a total
        // refuseAnyShortfall already checked: after all the additions the holding is at least its final figure, and each
        // removal only walks it down towards that. Ordering per movement was not enough -- a sale's re-entry removes the
        // same units its reversal gives back, and written first it took a holding already sold to nothing below zero.
        List<UnitEntry> inserts = new ArrayList<>();
        for (Redo r : redos) {
            UnitEntry e = r.original();
            inserts.add(UnitEntry.reversal(e, e.getSourceRef() + "#rev:" + e.getEntryId(), UnitLedger.SYSTEM, clock.instant()));
            inserts.add(UnitEntry.reEntry(e, r.units(), corrected, r.amount(),
                e.getSourceRef() + "#corr:" + corrected.getPriceId(), UnitLedger.SYSTEM, clock.instant()));
            BigDecimal difference = r.amount().subtract(e.getAmount());
            moved = moved.add(difference);
            boolean paidAlready = settleWithItsExit(tenantId, e, difference, corrected, approvedBy);
            Map<String, Object> movement = new HashMap<>();
            movement.put("policyNumber", e.getPolicyNumber());
            movement.put("entryType", e.getType().name());
            movement.put("sourceRef", e.getSourceRef());
            movement.put("originalAmount", e.getAmount().toPlainString());
            movement.put("correctedAmount", r.amount().toPlainString());
            movement.put("paidAlready", paidAlready);
            movements.add(movement);
        }
        inserts.stream().sorted(java.util.Comparator.comparing(UnitEntry::getUnits).reversed()).forEach(entries::saveAndFlush);

        // The liability is carried at the fund's LATEST price, which may be a later date's than the one corrected.
        FundPrice latest = prices.findLatestApprovedBefore(tenantId, fund.getFundId(), LocalDate.of(9999, 12, 31))
            .orElse(corrected);
        pricingRun.trueUp(fund, latest, liability, liability.getCarried().add(moved));

        Map<String, Object> payload = new HashMap<>();
        payload.put("fundId", fund.getFundId().toString());
        payload.put("fundCode", fund.getCode());
        payload.put("valuationDate", corrected.getValuationDate().toString());
        payload.put("wrongPriceId", wrong.getPriceId().toString());
        payload.put("wrongPrice", wrong.getPrice().toPlainString());
        payload.put("correctedPriceId", corrected.getPriceId().toString());
        payload.put("correctedPrice", corrected.getPrice().toPlainString());
        payload.put("currencyCode", fund.getCurrency());
        payload.put("movements", movements);
        payload.put("approvedBy", approvedBy);
        events.publishEvent(DomainEventEnvelope.of("unitlinked.PriceCorrected", tenantId, payload));
    }

    /**
     * What the movement would have been at the corrected price. A buy of money M buys M / price again; a charge of
     * money M sells enough units to cover M again; an exit's sale sells the same units, now worth more or less.
     */
    private static Redo redo(UnitEntry e, BigDecimal price) {
        return switch (e.getType()) {
            case ALLOCATION, REINVESTMENT ->
                new Redo(e, UnitArithmetic.unitsBought(e.getAmount(), price), e.getAmount());
            case POLICY_FEE, COST_OF_INSURANCE ->
                new Redo(e, UnitArithmetic.unitsToSell(e.getAmount().negate(), price, new BigDecimal("1E12")).negate(), e.getAmount());
            case DEATH_SALE, SURRENDER_SALE, MATURITY_SALE, LAPSE_SALE, FREE_LOOK_SALE ->
                new Redo(e, e.getUnits(), UnitArithmetic.proceeds(e.getUnits().negate(), price).negate());
            default -> throw new IllegalStateException(e.getType() + " is not a priced movement");
        };
    }

    private void refuseAnyShortfall(UUID tenantId, List<Redo> redos) {
        Map<String, BigDecimal> change = new HashMap<>();
        for (Redo r : redos) {
            String key = r.original().getPolicyNumber() + "|" + r.original().getFundId();
            change.merge(key, r.units().subtract(r.original().getUnits()), BigDecimal::add);
        }
        for (var c : change.entrySet()) {
            String[] k = c.getKey().split("\\|");
            BigDecimal held = entries.sumUnits(tenantId, k[0], UUID.fromString(k[1]));
            if (held.add(c.getValue()).signum() < 0) {
                throw new UnitLinkedStateException("This correction would leave policy " + k[0] + " short of "
                    + held.add(c.getValue()).negate().toPlainString() + " units, which later movements have already sold."
                    + " Correct the later prices first; nothing was changed");
            }
        }
    }

    /**
     * A sale whose exit was already paid out: the difference waits as an adjustment. Priced but unpaid: the exit's
     * proceeds simply move, so its payout reads the corrected figure. A buy or a charge has no exit: nothing to do.
     */
    /** Whether the movement's exit had already been paid out -- the difference is then an adjustment. */
    private boolean settleWithItsExit(UUID tenantId, UnitEntry e, BigDecimal difference, FundPrice corrected, String approvedBy) {
        if (difference.signum() == 0 || e.getUnits().signum() >= 0) {
            return false;
        }
        Optional<ExitState> exit = exits.findByTenantIdAndSourceTypeAndSourceRef(tenantId, e.getSourceType(), e.getSourceRef());
        if (exit.isEmpty()) {
            return false;
        }
        // A sale's amount is negative: what the customer gets is its negation.
        BigDecimal toCustomer = difference.negate();
        if (exit.get().getStatus() == ExitState.Status.PAID) {
            adjustments.save(PriceCorrectionAdjustment.of(tenantId, e.getPolicyNumber(), corrected.getPriceId(),
                toCustomer, approvedBy));
            return true;
        }
        exit.get().addProceeds(toCustomer);
        exits.save(exit.get());
        return false;
    }
}
