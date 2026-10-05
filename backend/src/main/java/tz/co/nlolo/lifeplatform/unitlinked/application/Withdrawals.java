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
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;
import tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalInput;
import tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalView;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PendingOrder;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;
import tz.co.nlolo.lifeplatform.unitlinked.domain.WithdrawalRequest;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundPriceRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PendingOrderRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.SwitchRequestRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.WithdrawalRequestRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Partial withdrawals (U2, spec §3). One person requests a GROSS amount, from named funds or pro rata by value at the
 * latest prices; a second (finance or admin) approves, and the sale binds at THAT instant -- the approver never sees
 * the price first. Priced at the first approved price after it; the surrender charge for the policy year of the
 * valuation date comes off; the rest is paid through payment. A price fall that leaves a fund short sells the whole
 * fund and records the shortfall, never more units than are held. When the version says so, cover drops by the gross.
 */
@Component
class Withdrawals implements UnitsPricedListener {

    static final String SOURCE = "withdrawal";
    static final String PAYOUT_PURPOSE = "WITHDRAWAL_PAYOUT";
    private static final Set<String> IN_FORCE = Set.of("ACTIVE", "REINSTATED");
    private static final Set<String> LIVE = Set.of("REQUESTED", "APPROVED");
    private static final Map<String, Integer> PERIODS_PER_YEAR = Map.of("MONTHLY", 12, "QUARTERLY", 4, "ANNUALLY", 1, "SINGLE", 1);

    private final WithdrawalRequestRepository withdrawals;
    private final SwitchRequestRepository switches;
    private final PendingOrderRepository orders;
    private final UnitEntryRepository entries;
    private final FundRepository funds;
    private final FundPriceRepository prices;
    private final UnitLedger ledger;
    private final SurrenderCharges charges;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    Withdrawals(WithdrawalRequestRepository withdrawals, SwitchRequestRepository switches, PendingOrderRepository orders,
                UnitEntryRepository entries, FundRepository funds, FundPriceRepository prices,
                @org.springframework.context.annotation.Lazy UnitLedger ledger, SurrenderCharges charges, PolicyApi policyApi,
                ProductApi productApi, ApplicationEventPublisher events, @Qualifier("unitLinkedClock") Clock clock) {
        this.withdrawals = withdrawals;
        this.switches = switches;
        this.orders = orders;
        this.entries = entries;
        this.funds = funds;
        this.prices = prices;
        this.ledger = ledger;
        this.charges = charges;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    WithdrawalView request(String policyNumber, WithdrawalInput input, String by) {
        UUID tenantId = TenantContext.get();
        if (input == null || input.grossAmount() == null || input.grossAmount().signum() <= 0) {
            throw new IllegalArgumentException("A withdrawal is a gross amount above zero");
        }
        if (input.payeeRef() == null || input.payeeRef().isBlank()) {
            throw new IllegalArgumentException("A withdrawal needs the payee to pay");
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        List<WithdrawalRequest.Named> named = new ArrayList<>();
        if (input.funds() != null) {
            for (WithdrawalInput.Named n : input.funds()) {
                String code = n.fundCode() == null ? "" : n.fundCode().trim().toUpperCase();
                Fund fund = funds.findByTenantIdAndCode(tenantId, code)
                    .orElseThrow(() -> new IllegalArgumentException("Fund " + code + " is not in the fund register"));
                if (n.amount() == null || n.amount().signum() <= 0) {
                    throw new IllegalArgumentException("Each named fund's amount is above zero");
                }
                named.add(new WithdrawalRequest.Named(fund.getFundId(), n.amount()));
            }
        }
        check(policy, input.grossAmount(), named, null);
        WithdrawalRequest saved = withdrawals.save(new WithdrawalRequest(tenantId, policyNumber, input.grossAmount(), named,
            input.payeeRef().trim(), by, clock.instant()));
        return view(saved);
    }

    /** A second person approves; the checks run again, and the sale binds at this instant. */
    @Transactional
    WithdrawalView approve(UUID withdrawalId, String by) {
        UUID tenantId = TenantContext.get();
        WithdrawalRequest request = load(withdrawalId);
        PolicyView policy = policyApi.getPolicy(request.getPolicyNumber());
        check(policy, request.getGrossAmount(), request.getNamed(), request.getWithdrawalId());
        Instant at = clock.instant();
        request.approve(by, at);
        Map<UUID, BigDecimal> amounts = request.getNamed().isEmpty()
            ? proRata(request.getPolicyNumber(), request.getGrossAmount())
            : namedAmounts(request.getNamed());
        for (Map.Entry<UUID, BigDecimal> e : amounts.entrySet()) {
            if (e.getValue().signum() <= 0) {
                continue;
            }
            Fund fund = funds.findByTenantIdAndFundId(tenantId, e.getKey()).orElseThrow();
            orders.save(PendingOrder.sellAmount(tenantId, request.getPolicyNumber(), fund.getFundId(), e.getValue(),
                PendingOrder.Purpose.WITHDRAWAL, at, fund.getCutOffTime(), SOURCE, request.getWithdrawalId().toString()));
        }
        return view(withdrawals.save(request));
    }

    /**
     * Every rule a withdrawal must meet, at request and again at approval (spec §3). {@code self} is the withdrawal
     * itself on approval, so it does not count as "another withdrawal in flight".
     */
    private void check(PolicyView policy, BigDecimal gross, List<WithdrawalRequest.Named> named, UUID self) {
        UUID tenantId = TenantContext.get();
        String policyNumber = policy.policyNumber();
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        UnitLinkedOptions options = plan.options();
        if (!plan.unitLinked() || !options.withdrawalsOffered()) {
            throw new UnitLinkedStateException("This product does not offer partial withdrawals");
        }
        if (!IN_FORCE.contains(policy.status().name()) || ledger.isFrozen(policyNumber)) {
            throw new UnitLinkedStateException("Policy " + policyNumber + " is not in force; nothing can be withdrawn");
        }
        LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : policy.issueDate();
        if (Period.between(start, today()).getYears() < plan.minimumSurrenderYears()) {
            throw new UnitLinkedStateException("Policy " + policyNumber + " has no withdrawal value until "
                + plan.minimumSurrenderYears() + " years");
        }
        withdrawals.findFirstByTenantIdAndPolicyNumberAndStatusIn(tenantId, policyNumber, LIVE)
            .filter(w -> !w.getWithdrawalId().equals(self))
            .ifPresent(w -> {
                throw new UnitLinkedStateException("A withdrawal is already in flight on policy " + policyNumber);
            });
        policyApi.findLatestSurrenderRequest(policyNumber).filter(r -> LIVE.contains(r.status())).ifPresent(r -> {
            throw new UnitLinkedStateException("A surrender is in flight on policy " + policyNumber);
        });
        if (switches.existsByTenantIdAndPolicyNumberAndStatus(tenantId, policyNumber, "WAITING")) {
            throw new UnitLinkedStateException("A switch is waiting on policy " + policyNumber + "; withdraw once it has priced");
        }
        if (gross.compareTo(options.minimumWithdrawal()) < 0) {
            throw new IllegalArgumentException("A withdrawal is at least " + money(options.minimumWithdrawal()) + " "
                + policy.premiumCurrency());
        }
        Map<UUID, BigDecimal> values = valuesAtLatest(policyNumber);
        if (!named.isEmpty()) {
            BigDecimal sum = named.stream().map(WithdrawalRequest.Named::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sum.compareTo(gross) != 0) {
                throw new IllegalArgumentException("The named funds' amounts total " + money(sum) + "; they must total the"
                    + " withdrawal of " + money(gross));
            }
            for (WithdrawalRequest.Named n : named) {
                BigDecimal value = values.getOrDefault(n.getFundId(), BigDecimal.ZERO);
                if (n.getAmount().compareTo(value) > 0) {
                    throw new IllegalArgumentException("Fund " + code(n.getFundId()) + " is worth about " + money(value)
                        + " " + policy.premiumCurrency() + "; less than the " + money(n.getAmount()) + " asked of it");
                }
            }
        }
        BigDecimal total = values.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remaining = total.subtract(gross);
        if (remaining.compareTo(options.minimumRemainingValue()) < 0) {
            throw new IllegalArgumentException("This withdrawal would leave about " + money(remaining.max(BigDecimal.ZERO))
                + " " + policy.premiumCurrency() + "; at least " + money(options.minimumRemainingValue()) + " "
                + policy.premiumCurrency() + " must stay in the policy");
        }
        if (options.withdrawalReducesSumAssured()) {
            BigDecimal floor = annualPremium(policy).multiply(plan.sumAssuredMultipleMin());
            BigDecimal cut = policy.sumAssuredAmount().subtract(gross);
            if (cut.compareTo(floor) < 0) {
                throw new IllegalArgumentException("Cutting the cover by this withdrawal would leave " + money(cut) + " "
                    + policy.premiumCurrency() + ", below the " + money(floor) + " " + policy.premiumCurrency()
                    + " this product's minimum allows");
            }
        }
    }

    /**
     * Each fund the policy holds, valued at its latest approved price on or before today. A fund with no approved price
     * yet refuses the withdrawal in its own words -- it is never counted as zero, and never priced at an older price.
     */
    private Map<UUID, BigDecimal> valuesAtLatest(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Map<UUID, BigDecimal> values = new LinkedHashMap<>();
        for (Map.Entry<UUID, BigDecimal> held : ledger.holdings(policyNumber).entrySet()) {
            if (held.getValue().signum() <= 0) {
                continue;
            }
            FundPrice price = prices.findLatestApprovedBefore(tenantId, held.getKey(), today().plusDays(1))
                .orElseThrow(() -> new IllegalArgumentException("Fund " + code(held.getKey())
                    + " has no price yet; nothing can be withdrawn until it has one"));
            values.put(held.getKey(), UnitArithmetic.proceeds(held.getValue(), price.getPrice()));
        }
        return values;
    }

    /** The gross split across the funds held, by value at the latest prices, largest remainder to the cent. */
    private Map<UUID, BigDecimal> proRata(String policyNumber, BigDecimal gross) {
        Map<UUID, BigDecimal> values = valuesAtLatest(policyNumber);
        List<UUID> ids = values.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList();
        List<BigDecimal> parts = UnitArithmetic.split(gross, ids.stream()
            .map(id -> new UnitArithmetic.Weighted(id.toString(), values.get(id))).toList());
        Map<UUID, BigDecimal> amounts = new LinkedHashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            amounts.put(ids.get(i), parts.get(i));
        }
        return amounts;
    }

    private static Map<UUID, BigDecimal> namedAmounts(List<WithdrawalRequest.Named> named) {
        Map<UUID, BigDecimal> amounts = new LinkedHashMap<>();
        named.forEach(n -> amounts.put(n.getFundId(), n.getAmount()));
        return amounts;
    }

    /** Each fund's sale priced; the last one charges, pays and -- when the version says so -- cuts the cover. */
    @Override
    public void afterPriced(PendingOrder order, UnitEntry entry) {
        if (order.getPurpose() != PendingOrder.Purpose.WITHDRAWAL) {
            return;
        }
        UUID tenantId = TenantContext.get();
        WithdrawalRequest request = load(UUID.fromString(order.getSourceRef()));
        BigDecimal raised = entry == null ? BigDecimal.ZERO : entry.getAmount().negate();
        request.sold(raised, order.getAmount().subtract(raised));
        if (orders.countWaiting(tenantId, SOURCE, order.getSourceRef()) > 0) {
            withdrawals.save(request);
            return;
        }
        PolicyView policy = policyApi.getPolicy(request.getPolicyNumber());
        LocalDate valuedOn = entry != null ? entry.getValuationDate() : today();
        BigDecimal charge = SurrenderCharges.charge(request.getProceeds(), charges.percentFor(policy, valuedOn));
        if (charge.signum() > 0) {
            entries.save(UnitEntry.money(tenantId, request.getPolicyNumber(), UnitEntry.Type.SURRENDER_CHARGE, charge.negate(),
                valuedOn, SOURCE, request.getWithdrawalId() + ":surrender-charge", null, UnitLedger.SYSTEM, clock.instant()));
        }
        request.priced(charge);
        withdrawals.save(request);
        String ref = request.getWithdrawalId().toString();
        Map<String, Object> priced = new HashMap<>();
        priced.put("withdrawalId", ref);
        priced.put("policyNumber", request.getPolicyNumber());
        priced.put("sourceRef", SOURCE + ":" + ref);
        priced.put("proceeds", request.getProceeds().toPlainString());
        priced.put("surrenderCharge", charge.toPlainString());
        priced.put("currencyCode", policy.premiumCurrency());
        events.publishEvent(DomainEventEnvelope.of("unitlinked.WithdrawalPriced", tenantId, priced));
        BigDecimal net = request.getProceeds().subtract(charge);
        if (net.signum() > 0) {
            Map<String, Object> payout = new HashMap<>();
            payout.put("purpose", PAYOUT_PURPOSE);
            payout.put("sourceRef", ref);
            payout.put("idempotencyKey", "unit-linked:" + SOURCE + ":" + ref);
            payout.put("policyNumber", request.getPolicyNumber());
            payout.put("payeeRef", request.getPayeeRef());
            payout.put("amount", Map.of("amount", net.toPlainString(), "currencyCode", policy.premiumCurrency()));
            events.publishEvent(DomainEventEnvelope.of("unitlinked.PayoutRequested", tenantId, payout));
        }
        if (productApi.resolveUnitLinkedPlan(policy.productVersionId()).options().withdrawalReducesSumAssured()) {
            policyApi.reduceUnitLinkedSumAssured(request.getPolicyNumber(), request.getGrossAmount(),
                "Partial withdrawal " + ref, UnitLedger.SYSTEM);
        }
    }

    /** payment paid the withdrawal: PAID once, and the cash leg published for the ledger (keyed on the disbursement). */
    @Transactional
    void onPaid(PaymentEventListener.Paid paid) {
        if (!PAYOUT_PURPOSE.equals(paid.purpose())) {
            return;
        }
        withdrawals.findByTenantIdAndWithdrawalId(TenantContext.get(), UUID.fromString(paid.sourceRef()))
            .filter(w -> "PRICED".equals(w.getStatus()))
            .ifPresent(w -> {
                w.paid();
                withdrawals.save(w);
                events.publishEvent(DomainEventEnvelope.of("unitlinked.PayoutPaid", TenantContext.get(),
                    paid.payload(w.getPolicyNumber())));
            });
    }

    /** An exit freezes the policy: a withdrawal not yet priced is cancelled -- its waiting sales go with the exit's. */
    void cancelLive(String policyNumber) {
        withdrawals.findFirstByTenantIdAndPolicyNumberAndStatusIn(TenantContext.get(), policyNumber, LIVE)
            .ifPresent(w -> {
                w.cancel();
                withdrawals.save(w);
            });
    }

    @Transactional(readOnly = true)
    List<WithdrawalView> list(String policyNumber) {
        return withdrawals.findByTenantIdAndPolicyNumberOrderByRequestedAtDesc(TenantContext.get(), policyNumber).stream()
            .map(this::view).toList();
    }

    private WithdrawalRequest load(UUID withdrawalId) {
        return withdrawals.findByTenantIdAndWithdrawalId(TenantContext.get(), withdrawalId)
            .orElseThrow(() -> new tz.co.nlolo.lifeplatform.unitlinked.api.FundNotFoundException("Withdrawal " + withdrawalId));
    }

    /** The request, with its estimates at the latest prices while it is not yet priced. */
    private WithdrawalView view(WithdrawalRequest w) {
        BigDecimal percent = null;
        BigDecimal estimatedCharge = null;
        BigDecimal estimatedNet = null;
        BigDecimal estimatedRemaining = null;
        BigDecimal newSumAssured = null;
        PolicyView policy = policyApi.getPolicy(w.getPolicyNumber());
        if (w.isLive()) {
            percent = charges.percentFor(policy, today());
            estimatedCharge = SurrenderCharges.charge(w.getGrossAmount(), percent);
            estimatedNet = w.getGrossAmount().subtract(estimatedCharge);
            try {
                estimatedRemaining = valuesAtLatest(w.getPolicyNumber()).values().stream()
                    .reduce(BigDecimal.ZERO, BigDecimal::add).subtract(w.getGrossAmount()).max(BigDecimal.ZERO);
            } catch (IllegalArgumentException noPriceYet) {
                estimatedRemaining = null;
            }
        }
        if (productApi.resolveUnitLinkedPlan(policy.productVersionId()).options().withdrawalReducesSumAssured()) {
            newSumAssured = "PRICED".equals(w.getStatus()) || "PAID".equals(w.getStatus())
                ? policy.sumAssuredAmount() : policy.sumAssuredAmount().subtract(w.getGrossAmount());
        }
        return new WithdrawalView(w.getWithdrawalId(), w.getPolicyNumber(), w.getGrossAmount(),
            w.getNamed().stream().map(n -> new WithdrawalView.Named(code(n.getFundId()), n.getAmount())).toList(),
            w.getPayeeRef(), w.getStatus(), percent, estimatedCharge, estimatedNet, estimatedRemaining, newSumAssured,
            w.getProceeds(), w.getSurrenderCharge(), w.getShortfall(), w.getRequestedBy(), w.getRequestedAt(),
            w.getApprovedBy(), w.getApprovedAt());
    }

    private static BigDecimal annualPremium(PolicyView policy) {
        BigDecimal premium = policy.premiumAmount() == null ? BigDecimal.ZERO : policy.premiumAmount();
        return premium.multiply(BigDecimal.valueOf(PERIODS_PER_YEAR.getOrDefault(policy.premiumFrequency(), 1)));
    }

    private String code(UUID fundId) {
        return funds.findByTenantIdAndFundId(TenantContext.get(), fundId).map(Fund::getCode).orElse("?");
    }

    private LocalDate today() {
        return BindingRule.civilDate(clock.instant());
    }

    private static String money(BigDecimal amount) {
        return String.format("%,.2f", amount);
    }
}
