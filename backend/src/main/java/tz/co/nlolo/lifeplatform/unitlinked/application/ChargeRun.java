package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.party.api.PartyDetailView;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.unitlinked.domain.ChargeDates;
import tz.co.nlolo.lifeplatform.unitlinked.domain.CostOfInsurance;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FrozenPolicy;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.domain.NoticeLog;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PendingOrder;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundPriceRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.NoticeLogRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PendingOrderRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The monthly charges (spec §6). On each charge date the policy fee and the cost of insurance are sold from units
 * in proportion to the funds' values, bound to the charge date and priced FORWARD. The cost of insurance is sized
 * at the latest price strictly before the charge date -- the insurer's own calculation, not a customer's
 * transaction. When a date's orders are all priced: a shortfall is written off (never billed); an empty fund
 * lapses the policy on exhaustion; a low one warns the customer, at most once a month.
 */
@Component
class ChargeRun implements UnitsPricedListener {

    private static final Logger log = LoggerFactory.getLogger(ChargeRun.class);
    static final String CHARGE = "charge";

    private final PendingOrderRepository orders;
    private final UnitEntryRepository entries;
    private final FundRepository funds;
    private final FundPriceRepository prices;
    private final NoticeLogRepository notices;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final PartyApi partyApi;
    private final UnitLedger ledger;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    ChargeRun(PendingOrderRepository orders, UnitEntryRepository entries, FundRepository funds, FundPriceRepository prices,
              NoticeLogRepository notices, PolicyApi policyApi, ProductApi productApi, PartyApi partyApi,
              @Lazy UnitLedger ledger, ApplicationEventPublisher events, @Qualifier("unitLinkedClock") Clock clock) {
        this.orders = orders;
        this.entries = entries;
        this.funds = funds;
        this.prices = prices;
        this.notices = notices;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    static String prefix(String policyNumber, LocalDate chargeDate) {
        return CHARGE + ":" + policyNumber + ":" + chargeDate + ":";
    }

    /** Every charge date due by {@code today} that has not run yet, oldest first. */
    @Transactional
    void runDue(String policyNumber, LocalDate today) {
        PolicyView policy = policyApi.getPolicy(policyNumber);
        for (LocalDate date : ChargeDates.between(policy.issueDate(), policy.issueDate(), today)) {
            runFor(policyNumber, date);
        }
    }

    /** One charge date. Idempotent: a date already run places nothing. */
    @Transactional
    void runFor(String policyNumber, LocalDate chargeDate) {
        UUID tenantId = TenantContext.get();
        String prefix = prefix(policyNumber, chargeDate);
        if (!orders.findBySourcePrefix(tenantId, CHARGE, prefix).isEmpty()
                || !entries.findBySourcePrefix(tenantId, CHARGE, prefix).isEmpty()) {
            return;
        }
        if (ledger.isFrozen(policyNumber)) {
            return;
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        if (policy.status() != PolicyStatus.ACTIVE && policy.status() != PolicyStatus.REINSTATED) {
            return;
        }
        // Not until a premium has actually bought units: a policy waiting for its first premium is not "exhausted".
        if (!entries.existsByTenantIdAndPolicyNumberAndEntryType(tenantId, policyNumber, "ALLOCATION")) {
            return;
        }
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        Map<UUID, BigDecimal> values = valuesBefore(policyNumber, chargeDate);
        BigDecimal fundValue = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        UUID life = policy.lifeAssuredPartyId() != null ? policy.lifeAssuredPartyId() : policy.policyholderPartyId();
        PartyDetailView person = partyApi.getPartyDetail(life);
        String sex = plan.mortalityBasis() == UnitLinkedPlan.MortalityBasis.BY_SEX
            ? (person.sex() == null ? null : person.sex().name()) : null;
        if (plan.mortalityBasis() == UnitLinkedPlan.MortalityBasis.BY_SEX && sex == null) {
            log.error("Unit-linked policy {}: no recorded sex for a BY_SEX mortality table; the {} charge was not taken",
                policyNumber, chargeDate);
            events.publishEvent(DomainEventEnvelope.of("unitlinked.ChargeRefused", tenantId, Map.of(
                "policyNumber", policyNumber, "chargeDate", chargeDate.toString(), "reason", "NO_RECORDED_SEX")));
            return;
        }
        int age = Period.between(person.dateOfBirth(), chargeDate).getYears();
        BigDecimal coi = CostOfInsurance.monthly(plan.annualRatePerMille(age, sex),
            CostOfInsurance.sumAtRisk(plan.deathRule(), policy.sumAssuredAmount(), fundValue));
        BigDecimal fee = plan.monthlyPolicyFee();

        if (fundValue.signum() <= 0) {
            // Nothing left to sell: the whole month's charges are written off and the fund is exhausted.
            writeOff(tenantId, policyNumber, chargeDate, fee.add(coi));
            exhausted(tenantId, policy, plan, chargeDate);
            return;
        }
        // Bound to the charge date itself (the run is before every cut-off). Priced by the first price approved
        // after the orders exist -- never one already known when they were placed.
        java.time.Instant at = chargeDate.atStartOfDay(tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule.CIVIL_ZONE).toInstant();
        place(tenantId, policyNumber, values, fee, "POLICY_FEE", prefix + "fee", at);
        place(tenantId, policyNumber, values, coi, "COST_OF_INSURANCE", prefix + "coi", at);
    }

    /** The policy's units per fund, valued at the latest approved price strictly before {@code date}. */
    private Map<UUID, BigDecimal> valuesBefore(String policyNumber, LocalDate date) {
        UUID tenantId = TenantContext.get();
        Map<UUID, BigDecimal> values = new java.util.LinkedHashMap<>();
        for (var held : ledger.holdings(policyNumber).entrySet()) {
            if (held.getValue().signum() <= 0) {
                continue;
            }
            FundPrice price = prices.findLatestApprovedBefore(tenantId, held.getKey(), date)
                .orElseThrow(() -> new IllegalStateException("Fund " + held.getKey() + " holds units of policy "
                    + policyNumber + " but has no approved price before " + date));
            values.put(held.getKey(), UnitArithmetic.proceeds(held.getValue(), price.getPrice()));
        }
        return values;
    }

    private void place(UUID tenantId, String policyNumber, Map<UUID, BigDecimal> values, BigDecimal amount,
                       String entryType, String sourceRef, java.time.Instant at) {
        if (amount.signum() <= 0) {
            return;
        }
        List<UUID> fundIds = new ArrayList<>(values.keySet());
        List<BigDecimal> parts = UnitArithmetic.split(amount, fundIds.stream()
            .map(id -> new UnitArithmetic.Weighted(id.toString(), values.get(id))).toList());
        for (int i = 0; i < fundIds.size(); i++) {
            if (parts.get(i).signum() <= 0) {
                continue;
            }
            Fund fund = funds.findByTenantIdAndFundId(tenantId, fundIds.get(i)).orElseThrow();
            LocalTime cutOff = fund.getCutOffTime();
            orders.save(PendingOrder.sellCharge(tenantId, policyNumber, fund.getFundId(), parts.get(i), entryType, at,
                cutOff.isAfter(LocalTime.MIDNIGHT) ? cutOff : LocalTime.of(0, 0, 1), CHARGE, sourceRef));
        }
    }

    /** A charge date's last order priced: write off any shortfall, then exhaust or warn. */
    @Override
    public void afterPriced(PendingOrder order, UnitEntry entry) {
        if (order.getPurpose() != PendingOrder.Purpose.CHARGES) {
            return;
        }
        UUID tenantId = TenantContext.get();
        String ref = order.getSourceRef();
        String prefix = ref.substring(0, ref.lastIndexOf(':') + 1);
        List<PendingOrder> dated = orders.findBySourcePrefix(tenantId, CHARGE, prefix);
        if (dated.stream().anyMatch(PendingOrder::isWaiting)) {
            return;
        }
        String policyNumber = order.getPolicyNumber();
        LocalDate chargeDate = LocalDate.parse(prefix.substring(prefix.indexOf(':', CHARGE.length() + 1) + 1, prefix.length() - 1));
        List<UnitEntry> taken = entries.findBySourcePrefix(tenantId, CHARGE, prefix).stream()
            .filter(e -> e.getType() == UnitEntry.Type.POLICY_FEE || e.getType() == UnitEntry.Type.COST_OF_INSURANCE).toList();
        BigDecimal asked = dated.stream().map(PendingOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = sum(taken, UnitEntry.Type.POLICY_FEE);
        BigDecimal coi = sum(taken, UnitEntry.Type.COST_OF_INSURANCE);
        BigDecimal shortfall = asked.subtract(fee).subtract(coi);
        if (shortfall.signum() > 0) {
            writeOff(tenantId, policyNumber, chargeDate, shortfall);
        }
        Fund fund = funds.findByTenantIdAndFundId(tenantId, order.getFundId()).orElseThrow();
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", policyNumber);
        payload.put("chargeDate", chargeDate.toString());
        payload.put("sourceRef", prefix);
        payload.put("policyFee", fee.toPlainString());
        payload.put("costOfInsurance", coi.toPlainString());
        payload.put("writtenOff", shortfall.max(BigDecimal.ZERO).toPlainString());
        payload.put("currencyCode", fund.getCurrency());
        events.publishEvent(DomainEventEnvelope.of("unitlinked.ChargesTaken", tenantId, payload));

        PolicyView policy = policyApi.getPolicy(policyNumber);
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        boolean empty = ledger.holdings(policyNumber).values().stream().allMatch(u -> u.signum() <= 0);
        if (empty) {
            exhausted(tenantId, policy, plan, chargeDate);
            return;
        }
        warnIfLow(tenantId, policy, plan, chargeDate, fee.add(coi).add(shortfall.max(BigDecimal.ZERO)));
    }

    private static BigDecimal sum(List<UnitEntry> taken, UnitEntry.Type type) {
        return taken.stream().filter(e -> e.getType() == type).map(e -> e.getAmount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void writeOff(UUID tenantId, String policyNumber, LocalDate chargeDate, BigDecimal amount) {
        entries.save(UnitEntry.money(tenantId, policyNumber, UnitEntry.Type.WRITE_OFF, amount, chargeDate, CHARGE,
            prefix(policyNumber, chargeDate) + "writeoff", null, UnitLedger.SYSTEM, clock.instant()));
    }

    /**
     * No units left to meet the charges: frozen, and lapsed. Under EXHAUSTION that is the rule; under NON_PAYMENT an
     * empty fund cannot carry cover either, so it lapses the same way (plan Task 6 Step 3).
     */
    private void exhausted(UUID tenantId, PolicyView policy, UnitLinkedPlan plan, LocalDate on) {
        ledger.freeze(policy.policyNumber(), FrozenPolicy.Reason.EXHAUSTED, "exhausted:" + on);
        policyApi.lapseExhaustedAccount(policy.policyNumber(), on, "FUND_EXHAUSTED");
        events.publishEvent(DomainEventEnvelope.of("unitlinked.FundExhausted", tenantId, Map.of(
            "policyNumber", policy.policyNumber(), "policyholderPartyId", policy.policyholderPartyId().toString(),
            "on", on.toString(), "lapseRule", plan.lapseRule().name())));
    }

    /** The fund now covers fewer than the version's months of charges: tell the customer, at most once a month. */
    private void warnIfLow(UUID tenantId, PolicyView policy, UnitLinkedPlan plan, LocalDate on, BigDecimal monthly) {
        if (monthly.signum() <= 0) {
            return;
        }
        BigDecimal value = valuesBefore(policy.policyNumber(), on.plusDays(1)).values().stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal months = value.divide(monthly, 1, java.math.RoundingMode.DOWN);
        if (months.compareTo(BigDecimal.valueOf(plan.lowFundWarningMonths())) >= 0) {
            return;
        }
        String month = YearMonth.from(on).toString();
        if (notices.sent(tenantId, policy.policyNumber(), "LOW_FUND", month)) {
            return;
        }
        notices.save(new NoticeLog(tenantId, policy.policyNumber(), "LOW_FUND", month));
        events.publishEvent(DomainEventEnvelope.of("unitlinked.LowFund", tenantId, Map.of(
            "policyNumber", policy.policyNumber(), "policyholderPartyId", policy.policyholderPartyId().toString(),
            "fundValue", value.toPlainString(), "monthsCovered", months.toPlainString(),
            "currencyCode", policy.premiumCurrency())));
    }
}
