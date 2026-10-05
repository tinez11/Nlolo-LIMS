package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedOptions;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.unitlinked.api.SwitchInput;
import tz.co.nlolo.lifeplatform.unitlinked.api.SwitchView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundLiability;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PremiumSplit;
import tz.co.nlolo.lifeplatform.unitlinked.domain.SwitchRequest;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundLiabilityRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundPriceRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.SwitchRequestRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Fund switches (U2, spec §2). One staff member records one, audited by its own row. Both legs are priced on ONE
 * valuation date -- the latest of the involved funds' bindings, so no leg is priced on a date the customer could already
 * see -- and the switch executes, inside a pricing run, at the first date on or after it on which every involved fund
 * has an approved price: a holiday in one fund delays the whole switch, never splits it across dates. Beyond the
 * version's free switches in a policy year, its fee is taken from the proceeds. The value moved stays in 2150; each
 * fund's carried liability moves with it.
 */
@Component
class Switches {

    static final String SOURCE = "switch";
    private static final Set<String> IN_FORCE = Set.of("ACTIVE", "REINSTATED");

    private final SwitchRequestRepository switches;
    private final FundRepository funds;
    private final FundPriceRepository prices;
    private final UnitEntryRepository entries;
    private final FundLiabilityRepository liabilities;
    private final UnitLedger ledger;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    Switches(SwitchRequestRepository switches, FundRepository funds, FundPriceRepository prices, UnitEntryRepository entries,
             FundLiabilityRepository liabilities, @org.springframework.context.annotation.Lazy UnitLedger ledger,
             PolicyApi policyApi, ProductApi productApi, ApplicationEventPublisher events,
             @Qualifier("unitLinkedClock") Clock clock) {
        this.switches = switches;
        this.funds = funds;
        this.prices = prices;
        this.entries = entries;
        this.liabilities = liabilities;
        this.ledger = ledger;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    SwitchView request(String policyNumber, SwitchInput input, String by) {
        UUID tenantId = TenantContext.get();
        PolicyView policy = policyApi.getPolicy(policyNumber);
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        if (!plan.unitLinked() || !plan.options().switchingOffered()) {
            throw new UnitLinkedStateException("This product does not offer fund switches");
        }
        if (!IN_FORCE.contains(policy.status().name()) || ledger.isFrozen(policyNumber)) {
            throw new UnitLinkedStateException("Policy " + policyNumber + " is not in force; nothing can be switched");
        }
        if (switches.existsByTenantIdAndPolicyNumberAndStatus(tenantId, policyNumber, "WAITING")) {
            throw new UnitLinkedStateException("A switch is already waiting on policy " + policyNumber);
        }
        if (input == null || input.out() == null || input.out().isEmpty()) {
            throw new IllegalArgumentException("A switch names at least one fund to move out of");
        }
        Map<UUID, BigDecimal> held = ledger.holdings(policyNumber);
        List<SwitchRequest.Leg> legs = new ArrayList<>();
        for (SwitchInput.Out out : input.out()) {
            String code = out.fundCode() == null ? "" : out.fundCode().trim().toUpperCase();
            Fund fund = funds.findByTenantIdAndCode(tenantId, code)
                .orElseThrow(() -> new IllegalArgumentException("Fund " + code + " is not in the fund register"));
            if (held.getOrDefault(fund.getFundId(), BigDecimal.ZERO).signum() <= 0) {
                throw new IllegalArgumentException("Policy " + policyNumber + " holds no units of " + code);
            }
            if (out.percent() < 1 || out.percent() > 100) {
                throw new IllegalArgumentException("Each fund's share is a whole percent from 1 to 100");
            }
            if (legs.stream().anyMatch(l -> l.isOut() && l.getFundId().equals(fund.getFundId()))) {
                throw new IllegalArgumentException("Fund " + code + " appears twice in the switch");
            }
            legs.add(new SwitchRequest.Leg(fund.getFundId(), "OUT", out.percent()));
        }
        for (PremiumSplit.Share share : SplitRules.resolve(input.into(), plan, funds)) {
            legs.add(new SwitchRequest.Leg(share.getFundId(), "IN", share.getPercent()));
        }
        Instant at = clock.instant();
        LocalDate bound = legs.stream().map(l -> funds.findByTenantIdAndFundId(tenantId, l.getFundId()).orElseThrow())
            .map(f -> BindingRule.boundDate(at, f.getCutOffTime())).max(Comparator.naturalOrder()).orElseThrow();
        return view(switches.save(new SwitchRequest(tenantId, policyNumber, at, by, bound, legs)));
    }

    /**
     * Called last in every pricing run, inside the approval's transaction: each waiting switch moving the fund just
     * priced executes once every one of its funds has an approved price for a common date on or after its binding.
     */
    void onPriceApproved(Fund fund, FundPrice price) {
        UUID tenantId = TenantContext.get();
        for (SwitchRequest sw : switches.findWaitingInvolving(tenantId, fund.getFundId(), price.getValuationDate())) {
            commonPricedDate(sw).ifPresent(date -> execute(sw, date));
        }
    }

    /** The first date on or after the binding on which every involved fund has an APPROVED price; empty until then. */
    private Optional<LocalDate> commonPricedDate(SwitchRequest sw) {
        List<UUID> involved = sw.getLegs().stream().map(SwitchRequest.Leg::getFundId).distinct().toList();
        return prices.findApprovedFrom(sw.getTenantId(), involved.get(0), sw.getBoundDate()).stream()
            .map(FundPrice::getValuationDate)
            .filter(d -> involved.stream().allMatch(f -> prices.findApproved(sw.getTenantId(), f, d).isPresent()))
            .findFirst();
    }

    private void execute(SwitchRequest sw, LocalDate date) {
        UUID tenantId = sw.getTenantId();
        String ref = sw.getSwitchId().toString();
        Instant now = clock.instant();
        BigDecimal proceeds = BigDecimal.ZERO;
        for (SwitchRequest.Leg out : sw.getLegs().stream().filter(SwitchRequest.Leg::isOut).toList()) {
            FundPrice p = prices.findApproved(tenantId, out.getFundId(), date).orElseThrow();
            BigDecimal held = entries.sumUnits(tenantId, sw.getPolicyNumber(), out.getFundId());
            if (held.signum() <= 0) {
                continue; // charges sold the fund out since the request: nothing of it to move
            }
            BigDecimal units = out.getPercent() == 100 ? held
                : held.multiply(BigDecimal.valueOf(out.getPercent())).divide(BigDecimal.valueOf(100), 6, RoundingMode.DOWN);
            BigDecimal money = UnitArithmetic.proceeds(units, p.getPrice());
            entries.save(UnitEntry.switched(tenantId, sw.getPolicyNumber(), out.getFundId(), UnitEntry.Type.SWITCH_OUT,
                units.negate(), p, money.negate(), ref, UnitLedger.SYSTEM, now));
            moveLiability(tenantId, out.getFundId(), money.negate());
            proceeds = proceeds.add(money);
        }
        BigDecimal fee = feeFor(sw, date).min(proceeds);
        if (fee.signum() > 0) {
            entries.save(UnitEntry.money(tenantId, sw.getPolicyNumber(), UnitEntry.Type.SWITCH_FEE, fee.negate(), date,
                SOURCE, ref, null, UnitLedger.SYSTEM, now));
        }
        BigDecimal net = proceeds.subtract(fee);
        List<SwitchRequest.Leg> into = sw.getLegs().stream().filter(l -> !l.isOut())
            .sorted(Comparator.comparing(l -> l.getFundId().toString())).toList();
        List<BigDecimal> parts = net.signum() > 0
            ? UnitArithmetic.split(net, into.stream()
                .map(l -> new UnitArithmetic.Weighted(l.getFundId().toString(), BigDecimal.valueOf(l.getPercent()))).toList())
            : into.stream().map(l -> BigDecimal.ZERO).toList();
        for (int i = 0; i < into.size(); i++) {
            if (parts.get(i).signum() <= 0) {
                continue;
            }
            FundPrice p = prices.findApproved(tenantId, into.get(i).getFundId(), date).orElseThrow();
            BigDecimal units = UnitArithmetic.unitsBought(parts.get(i), p.getPrice());
            entries.save(UnitEntry.switched(tenantId, sw.getPolicyNumber(), into.get(i).getFundId(), UnitEntry.Type.SWITCH_IN,
                units, p, parts.get(i), ref, UnitLedger.SYSTEM, now));
            moveLiability(tenantId, into.get(i).getFundId(), parts.get(i));
        }
        sw.executed(date, fee);
        switches.save(sw);
        Map<String, Object> payload = new HashMap<>();
        payload.put("switchId", ref);
        payload.put("policyNumber", sw.getPolicyNumber());
        payload.put("sourceRef", SOURCE + ":" + ref);
        payload.put("executedOn", date.toString());
        payload.put("moved", proceeds.toPlainString());
        payload.put("fee", fee.toPlainString());
        payload.put("currencyCode", policyApi.getPolicy(sw.getPolicyNumber()).premiumCurrency());
        events.publishEvent(DomainEventEnvelope.of("unitlinked.SwitchExecuted", tenantId, payload));
    }

    /** Beyond the version's free switches in the policy year of {@code date}: its fee; else nothing. */
    private BigDecimal feeFor(SwitchRequest sw, LocalDate date) {
        PolicyView policy = policyApi.getPolicy(sw.getPolicyNumber());
        UnitLinkedOptions options = productApi.resolveUnitLinkedPlan(policy.productVersionId()).options();
        LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : policy.issueDate();
        LocalDate yearStart = start.plusYears(Math.max(Period.between(start, date).getYears(), 0));
        long executedThisYear = switches.countExecutedBetween(sw.getTenantId(), sw.getPolicyNumber(), yearStart, date);
        return executedThisYear >= options.freeSwitchesPerYear() ? options.switchFee() : BigDecimal.ZERO;
    }

    /** A switch moves value between funds inside 2150: each fund's carried liability moves with it (plan D5). */
    private void moveLiability(UUID tenantId, UUID fundId, BigDecimal by) {
        FundLiability liability = liabilities.lockFor(tenantId, fundId).orElseGet(() -> new FundLiability(tenantId, fundId));
        liability.adjust(by);
        liabilities.save(liability);
    }

    /**
     * A price correction re-priced this switch's OUT leg (U2): {@code delta} more (positive) or less proceeds follow it
     * into its IN funds by the switch's split, at each fund's own approved price for the execution date -- the corrected
     * one where an IN fund is the fund corrected. Written as further SWITCH_IN entries keyed on the correction, so the
     * ledger stays append-only; a negative share sells units back out.
     */
    void followCorrection(UUID switchId, BigDecimal delta, FundPrice corrected) {
        UUID tenantId = TenantContext.get();
        SwitchRequest sw = switches.findByTenantIdAndSwitchId(tenantId, switchId)
            .orElseThrow(() -> new IllegalStateException("A switch leg names switch " + switchId + ", which does not exist"));
        List<SwitchRequest.Leg> into = sw.getLegs().stream().filter(l -> !l.isOut())
            .sorted(Comparator.comparing(l -> l.getFundId().toString())).toList();
        BigDecimal magnitude = delta.abs();
        List<BigDecimal> parts = UnitArithmetic.split(magnitude, into.stream()
            .map(l -> new UnitArithmetic.Weighted(l.getFundId().toString(), BigDecimal.valueOf(l.getPercent()))).toList());
        for (int i = 0; i < into.size(); i++) {
            BigDecimal part = delta.signum() < 0 ? parts.get(i).negate() : parts.get(i);
            if (part.signum() == 0) {
                continue;
            }
            UUID fundId = into.get(i).getFundId();
            FundPrice p = fundId.equals(corrected.getFundId()) ? corrected
                : prices.findApproved(tenantId, fundId, sw.getExecutedOn()).orElseThrow();
            BigDecimal units = UnitArithmetic.unitsBought(part.abs(), p.getPrice());
            entries.save(UnitEntry.switched(tenantId, sw.getPolicyNumber(), fundId, UnitEntry.Type.SWITCH_IN,
                part.signum() < 0 ? units.negate() : units, p, part,
                switchId + "#corr:" + corrected.getPriceId(), UnitLedger.SYSTEM, clock.instant()));
            moveLiability(tenantId, fundId, part);
        }
    }

    /** An exit freezes the policy: a switch still waiting is cancelled -- the exit sells everything. */
    void cancelWaiting(String policyNumber) {
        switches.findByTenantIdAndPolicyNumberAndStatus(TenantContext.get(), policyNumber, "WAITING")
            .ifPresent(sw -> {
                sw.cancel();
                switches.save(sw);
            });
    }

    @Transactional(readOnly = true)
    List<SwitchView> list(String policyNumber) {
        return switches.findByTenantIdAndPolicyNumberOrderByRequestedAtDesc(TenantContext.get(), policyNumber).stream()
            .map(this::view).toList();
    }

    SwitchView view(SwitchRequest sw) {
        UUID tenantId = sw.getTenantId();
        return new SwitchView(sw.getSwitchId(), sw.getPolicyNumber(), sw.getLegs().stream()
            .map(l -> new SwitchView.Leg(funds.findByTenantIdAndFundId(tenantId, l.getFundId()).map(Fund::getCode).orElse("?"),
                l.getSide(), l.getPercent())).toList(),
            sw.getBoundDate(), sw.getStatus(), sw.getExecutedOn(), sw.getFee(), sw.getRequestedBy(), sw.getRequestedAt());
    }
}
