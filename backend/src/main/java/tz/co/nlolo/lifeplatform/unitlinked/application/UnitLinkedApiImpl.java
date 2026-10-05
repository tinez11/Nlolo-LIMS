package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.unitlinked.api.AdjustmentView;
import tz.co.nlolo.lifeplatform.unitlinked.api.CreateFund;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundPriceView;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedApi;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FundPrice;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundPriceRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FrozenPolicyRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PendingOrderRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PolicyAllocationRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;
import tz.co.nlolo.lifeplatform.unitlinked.api.PolicyUnitsView;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UnitLinkedApiImpl implements UnitLinkedApi {

    private final FundRegister register;
    private final PremiumSplits premiumSplits;
    private final Switches switches;
    private final Withdrawals withdrawals;
    private final TopUps topUps;
    private final FundRepository funds;
    private final FundPriceRepository prices;
    private final PolicyAllocationRepository allocations;
    private final UnitEntryRepository entries;
    private final PendingOrderRepository orders;
    private final FrozenPolicyRepository frozen;
    private final Adjustments adjustments;
    private final Exits exits;

    UnitLinkedApiImpl(FundRegister register, FundRepository funds, FundPriceRepository prices,
                      PolicyAllocationRepository allocations, UnitEntryRepository entries, PendingOrderRepository orders,
                      FrozenPolicyRepository frozen, Adjustments adjustments, Exits exits, PremiumSplits premiumSplits,
                      Switches switches, Withdrawals withdrawals, TopUps topUps) {
        this.topUps = topUps;
        this.switches = switches;
        this.withdrawals = withdrawals;
        this.premiumSplits = premiumSplits;
        this.adjustments = adjustments;
        this.exits = exits;
        this.register = register;
        this.funds = funds;
        this.prices = prices;
        this.allocations = allocations;
        this.entries = entries;
        this.orders = orders;
        this.frozen = frozen;
    }

    @Override
    @Transactional(readOnly = true)
    public PolicyUnitsView units(String policyNumber) {
        UUID tenantId = TenantContext.get();
        Map<UUID, Fund> byId = funds.findByTenantIdOrderByCode(tenantId).stream()
            .collect(Collectors.toMap(Fund::getFundId, f -> f));
        LocalDate today = BindingRule.civilDate(register.now());
        List<PolicyUnitsView.Holding> holdings = new java.util.ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        for (Object[] row : entries.holdings(tenantId, policyNumber)) {
            Fund fund = byId.get((UUID) row[0]);
            BigDecimal units = (BigDecimal) row[1];
            currency = fund.getCurrency();
            // Shown at the latest approved price on or before today -- a display, never a posting.
            var price = prices.findLatestApprovedBefore(tenantId, fund.getFundId(), today.plusDays(1));
            BigDecimal value = price.map(p -> UnitArithmetic.proceeds(units.max(BigDecimal.ZERO), p.getPrice())).orElse(null);
            if (value != null) {
                total = total.add(value);
            }
            holdings.add(new PolicyUnitsView.Holding(fund.getCode(), fund.getName(), units,
                price.map(FundPrice::getPrice).orElse(null), price.map(FundPrice::getValuationDate).orElse(null), value));
        }
        holdings.sort(java.util.Comparator.comparing(PolicyUnitsView.Holding::fundCode));
        List<PolicyUnitsView.Pending> pending = orders
            .findByTenantIdAndPolicyNumberAndStatusOrderByReceivedAt(tenantId, policyNumber, "WAITING").stream()
            .map(o -> new PolicyUnitsView.Pending(o.getOrderId(), byId.get(o.getFundId()).getCode(), o.getSide().name(),
                o.getAmount(), o.isSellAll(), o.getPurpose().name(), o.getReceivedAt(), o.getBoundDate())).toList();
        List<PolicyUnitsView.Entry> ledger = entries.findByTenantIdAndPolicyNumberOrderByCreatedAt(tenantId, policyNumber).stream()
            .map(e -> new PolicyUnitsView.Entry(e.getEntryId(), e.getFundId() == null ? null : byId.get(e.getFundId()).getCode(),
                e.getType().name(), e.getUnits(), e.getPrice(), e.getAmount(), e.getValuationDate(), e.getBoundDate(),
                e.getSourceRef(), e.getCreatedAt())).toList();
        var frozenRow = frozen.findByTenantIdAndPolicyNumber(tenantId, policyNumber);
        // Switches are not pending orders (plan D1): their own list, the waiting one first.
        return new PolicyUnitsView(policyNumber, holdings, holdings.isEmpty() ? null : total, currency, pending, ledger,
            frozenRow.isPresent(), frozenRow.map(r -> r.getReason().name()).orElse(null), switches.list(policyNumber));
    }

    @Override
    @Transactional(readOnly = true)
    public List<WaitingCount> waiting(String fundCode) {
        Fund fund = register.fund(fundCode);
        return orders.waitingByDate(TenantContext.get(), fund.getFundId()).stream()
            .map(r -> new WaitingCount((LocalDate) r[0], ((Number) r[1]).longValue())).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AllocationView> allocationOf(String policyNumber) {
        Map<UUID, String> codes = codes();
        return allocations.findByTenantIdAndPolicyNumber(TenantContext.get(), policyNumber).stream()
            .map(a -> new AllocationView(codes.get(a.getFundId()), a.getPercent()))
            .sorted(java.util.Comparator.comparing(AllocationView::fundCode)).toList();
    }

    @Override
    public boolean decidesDeath(String policyNumber) {
        return exits.isUnitLinked(policyNumber);
    }

    @Override
    public tz.co.nlolo.lifeplatform.unitlinked.api.DeathValueView deathValue(UUID claimId, LocalDate dateOfDeath) {
        return exits.deathValue(claimId, dateOfDeath);
    }

    @Override
    public void payAwaitingExit(String policyNumber, String payeeRef, String by) {
        exits.payAwaiting(policyNumber, payeeRef);
    }

    @Override
    public tz.co.nlolo.lifeplatform.unitlinked.api.PremiumSplitView redirect(String policyNumber,
            List<tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice.Split> split, String by) {
        return premiumSplits.redirect(policyNumber, split, by);
    }

    @Override
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.PremiumSplitView> splitHistory(String policyNumber) {
        return premiumSplits.history(policyNumber);
    }

    @Override
    public tz.co.nlolo.lifeplatform.unitlinked.api.SwitchView requestSwitch(String policyNumber,
            tz.co.nlolo.lifeplatform.unitlinked.api.SwitchInput input, String by) {
        return switches.request(policyNumber, input, by);
    }

    @Override
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.SwitchView> listSwitches(String policyNumber) {
        return switches.list(policyNumber);
    }

    @Override
    public tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalView requestWithdrawal(String policyNumber,
            tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalInput input, String by) {
        return withdrawals.request(policyNumber, input, by);
    }

    @Override
    public tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalView approveWithdrawal(UUID withdrawalId, String by) {
        return withdrawals.approve(withdrawalId, by);
    }

    @Override
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.WithdrawalView> listWithdrawals(String policyNumber) {
        return withdrawals.list(policyNumber);
    }

    @Override
    public tz.co.nlolo.lifeplatform.unitlinked.api.TopUpView requestTopUp(String policyNumber,
            tz.co.nlolo.lifeplatform.unitlinked.api.TopUpInput input, String by, String idempotencyKey) {
        return topUps.request(policyNumber, input, by, idempotencyKey);
    }

    @Override
    public List<tz.co.nlolo.lifeplatform.unitlinked.api.TopUpView> listTopUps(String policyNumber) {
        return topUps.list(policyNumber);
    }

    @Override
    public FundPriceView proposeCorrection(UUID approvedPriceId, BigDecimal price, String reason, String proposedBy) {
        FundPrice p = register.proposeCorrection(approvedPriceId, price, reason, proposedBy);
        return view(p, codes().get(p.getFundId()));
    }

    @Override
    public List<AdjustmentView> listAdjustments(String status) {
        return adjustments.list(status);
    }

    @Override
    public AdjustmentView settleAdjustment(UUID adjustmentId, String payeeRef, String settledBy) {
        return adjustments.settle(adjustmentId, payeeRef, settledBy);
    }

    @Override
    public AdjustmentView waiveAdjustment(UUID adjustmentId, String reason, String waivedBy) {
        return adjustments.waive(adjustmentId, reason, waivedBy);
    }

    @Override
    public FundView createFund(CreateFund fund, String createdBy) {
        return view(register.create(fund, createdBy));
    }

    @Override
    public FundView closeFund(String code, String closedBy) {
        return view(register.close(code, closedBy));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FundView> listFunds() {
        return funds.findByTenantIdOrderByCode(TenantContext.get()).stream().map(UnitLinkedApiImpl::view).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FundView getFund(String code) {
        return view(register.fund(code));
    }

    @Override
    public FundPriceView proposePrice(String fundCode, LocalDate valuationDate, BigDecimal price, String moveReason,
                                      String proposedBy) {
        FundPrice p = register.propose(fundCode, valuationDate, price, moveReason, proposedBy);
        return view(p, register.fund(fundCode).getCode());
    }

    @Override
    public List<FundPriceView> proposePrices(String csv, String proposedBy) {
        List<FundPrice> proposed = register.proposeAll(csv, proposedBy);
        Map<UUID, String> codes = codes();
        return proposed.stream().map(p -> view(p, codes.get(p.getFundId()))).toList();
    }

    @Override
    public FundPriceView approvePrice(UUID priceId, String approvedBy) {
        FundPrice p = register.approve(priceId, approvedBy);
        return view(p, codes().get(p.getFundId()));
    }

    @Override
    public FundPriceView withdrawPrice(UUID priceId, String withdrawnBy) {
        FundPrice p = register.withdraw(priceId, withdrawnBy);
        return view(p, codes().get(p.getFundId()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FundPriceView> listPrices(String fundCode, LocalDate from, LocalDate to) {
        Fund fund = register.fund(fundCode);
        LocalDate end = to != null ? to : BindingRule.civilDate(register.now()).plusDays(7);
        LocalDate start = from != null ? from : end.minusDays(60);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("A price range needs its from date on or before its to date");
        }
        return prices.findBetween(TenantContext.get(), fund.getFundId(), start, end).stream()
            .map(p -> view(p, fund.getCode())).toList();
    }

    private Map<UUID, String> codes() {
        return funds.findByTenantIdOrderByCode(TenantContext.get()).stream()
            .collect(Collectors.toMap(Fund::getFundId, Fund::getCode, (a, b) -> a));
    }

    static FundView view(Fund f) {
        return new FundView(f.getFundId(), f.getCode(), f.getName(), f.getCurrency(), f.getAssetClass(),
            f.getAnnualManagementChargePercent(), f.getCutOffTime(), f.getStatus(), f.getCreatedBy(), f.getCreatedAt(),
            f.getClosedBy(), f.getClosedAt());
    }

    static FundPriceView view(FundPrice p, String fundCode) {
        return new FundPriceView(p.getPriceId(), p.getFundId(), fundCode, p.getValuationDate(), p.getPrice(),
            p.getStatus(), p.getMoveReason(), p.getSupersedesPriceId(), p.getProposedBy(), p.getProposedAt(),
            p.getApprovedBy(), p.getApprovedAt());
    }

}
