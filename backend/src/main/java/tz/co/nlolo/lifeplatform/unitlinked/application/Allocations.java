package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.underwriting.api.UnderwritingApi;
import tz.co.nlolo.lifeplatform.underwriting.api.UnitLinkedChoice;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundNotFoundException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PendingOrder;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PolicyAllocation;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PremiumSplit;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PendingOrderRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PolicyAllocationRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A unit-linked policy's fund split, written once at issue from the case's choice, and each collected premium's
 * way into units (spec §4-§5): the allocation charge taken at once, the rest split by the policy's percents into one
 * BUY per fund, each bound FORWARD by its fund's cut-off. When the premium's last order is priced, one
 * unitlinked.UnitsAllocated carries the whole premium for the ledger to post.
 */
@Component
class Allocations implements UnitsPricedListener {

    private static final Logger log = LoggerFactory.getLogger(Allocations.class);
    static final String PREMIUM = "premium";

    private final PolicyAllocationRepository allocations;
    private final FundRepository funds;
    private final PendingOrderRepository orders;
    private final UnitEntryRepository entries;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final UnderwritingApi underwritingApi;
    private final UnitLedger ledger;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final PremiumSplits premiumSplits;

    Allocations(PolicyAllocationRepository allocations, FundRepository funds, PendingOrderRepository orders,
                UnitEntryRepository entries, PolicyApi policyApi, ProductApi productApi, UnderwritingApi underwritingApi,
                @org.springframework.context.annotation.Lazy UnitLedger ledger, ApplicationEventPublisher events,
                @Qualifier("unitLinkedClock") Clock clock, PremiumSplits premiumSplits) {
        this.allocations = allocations;
        this.funds = funds;
        this.orders = orders;
        this.entries = entries;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.underwritingApi = underwritingApi;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
        this.premiumSplits = premiumSplits;
    }

    boolean isUnitLinked(String policyNumber) {
        return allocations.existsByTenantIdAndPolicyNumber(TenantContext.get(), policyNumber);
    }

    /** Idempotent: a redelivered PolicyIssued finds the split already written and writes nothing. */
    @Transactional
    void recordAtIssue(String policyNumber) {
        UUID tenantId = TenantContext.get();
        PolicyView policy = policyApi.getPolicy(policyNumber);
        if (!"UNIT_LINKED".equals(policy.productCategory())) {
            return;
        }
        if (allocations.existsByTenantIdAndPolicyNumber(tenantId, policyNumber)) {
            return;
        }
        // The case id is on the policy (plan C3); the PolicyIssued payload is unchanged.
        UnitLinkedChoice choice = underwritingApi.unitLinkedChoice(policy.underwritingCaseId())
            .orElseThrow(() -> new IllegalStateException("Unit-linked policy " + policyNumber
                + " has no fund choice on case " + policy.underwritingCaseId()));
        List<PremiumSplit.Share> shares = new java.util.ArrayList<>();
        for (UnitLinkedChoice.Split s : choice.split()) {
            Fund fund = funds.findByTenantIdAndCode(tenantId, s.fundCode())
                .orElseThrow(() -> new FundNotFoundException("Fund " + s.fundCode()));
            allocations.save(new PolicyAllocation(tenantId, policyNumber, fund.getFundId(), s.percent()));
            shares.add(new PremiumSplit.Share(fund.getFundId(), s.percent()));
        }
        // U2: the split is a history (spec §4); the case's choice is its first row. policy_allocation stays as the
        // marker every unit-linked listener gates on (isUnitLinked).
        premiumSplits.recordAtIssue(policyNumber, shares);
    }

    /**
     * {@code billing.PremiumCollected} on a unit-linked policy. Never invested into a frozen policy -- a death, a
     * surrender, a free-look or an exhausted fund -- which is refused loudly instead (an ERROR and an event staff
     * can see), never silently swallowed.
     */
    @Transactional
    void onPremiumCollected(String policyNumber, UUID invoiceId, BigDecimal premium, Instant collectedAt) {
        UUID tenantId = TenantContext.get();
        String ref = PREMIUM + ":" + invoiceId;
        if (orders.existsByTenantIdAndSourceTypeAndSourceRef(tenantId, PREMIUM, ref)
                || entries.existsByTenantIdAndSourceTypeAndSourceRefAndEntryType(tenantId, PREMIUM, ref, "ALLOCATION_CHARGE")) {
            return; // redelivered
        }
        if (ledger.isFrozen(policyNumber)) {
            log.error("Premium {} on unit-linked policy {} was collected while the policy is frozen; not invested",
                invoiceId, policyNumber);
            events.publishEvent(DomainEventEnvelope.of("unitlinked.PremiumNotInvested", tenantId, Map.of(
                "policyNumber", policyNumber, "invoiceId", invoiceId.toString(),
                "amount", premium.toPlainString(), "reason", "POLICY_FROZEN")));
            return;
        }
        PolicyView policy = policyApi.getPolicy(policyNumber);
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        LocalDate collectedOn = BindingRule.civilDate(collectedAt);
        LocalDate start = policy.commencementDate() != null ? policy.commencementDate() : policy.issueDate();
        int policyYear = Period.between(start, collectedOn).getYears() + 1;
        UnitArithmetic.Allocated allocated = UnitArithmetic.allocate(premium, plan.allocationPercent(Math.max(policyYear, 1)));

        if (allocated.charge().signum() > 0) {
            entries.save(UnitEntry.money(tenantId, policyNumber, UnitEntry.Type.ALLOCATION_CHARGE,
                allocated.charge().negate(), collectedOn, PREMIUM, ref, null, UnitLedger.SYSTEM, clock.instant()));
        }
        // The split in force when the money was RECEIVED (U2): a redirection reaches only later premiums.
        List<PremiumSplit.Share> split = premiumSplits.splitAt(policyNumber, collectedAt);
        List<BigDecimal> parts = UnitArithmetic.split(allocated.allocated(), split.stream()
            .map(a -> new UnitArithmetic.Weighted(a.getFundId().toString(), BigDecimal.valueOf(a.getPercent()))).toList());
        for (int i = 0; i < split.size(); i++) {
            if (parts.get(i).signum() <= 0) {
                continue;
            }
            Fund fund = funds.findByTenantIdAndFundId(tenantId, split.get(i).getFundId()).orElseThrow();
            orders.save(PendingOrder.buy(tenantId, policyNumber, fund.getFundId(), parts.get(i),
                PendingOrder.Purpose.ALLOCATION, collectedAt, fund.getCutOffTime(), PREMIUM, ref));
        }
    }

    /**
     * A top-up received (U2, spec §4): its own allocation percent -- not the policy year's band -- and its own split or
     * the one in force at the instant the money arrived, each fund's share bound FORWARD by its cut-off like any premium.
     * When its last order is priced, {@link #afterPriced} publishes UnitsAllocated for it as for a premium.
     */
    @Transactional
    void onTopUpReceived(tz.co.nlolo.lifeplatform.unitlinked.domain.TopUp topUp, Instant receivedAt) {
        UUID tenantId = TenantContext.get();
        String ref = TopUps.SOURCE + ":" + topUp.getTopUpId();
        PolicyView policy = policyApi.getPolicy(topUp.getPolicyNumber());
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        LocalDate receivedOn = BindingRule.civilDate(receivedAt);
        UnitArithmetic.Allocated allocated = UnitArithmetic.allocate(topUp.getAmount(), plan.options().topUpAllocationPercent());
        if (allocated.charge().signum() > 0) {
            entries.save(UnitEntry.money(tenantId, topUp.getPolicyNumber(), UnitEntry.Type.ALLOCATION_CHARGE,
                allocated.charge().negate(), receivedOn, TopUps.SOURCE, ref, null, UnitLedger.SYSTEM, clock.instant()));
        }
        List<PremiumSplit.Share> split = topUp.getSplit().isEmpty()
            ? premiumSplits.splitAt(topUp.getPolicyNumber(), receivedAt)
            : topUp.getSplit().stream().sorted(Comparator.comparing(s -> s.getFundId().toString())).toList();
        List<BigDecimal> parts = UnitArithmetic.split(allocated.allocated(), split.stream()
            .map(a -> new UnitArithmetic.Weighted(a.getFundId().toString(), BigDecimal.valueOf(a.getPercent()))).toList());
        for (int i = 0; i < split.size(); i++) {
            if (parts.get(i).signum() <= 0) {
                continue;
            }
            Fund fund = funds.findByTenantIdAndFundId(tenantId, split.get(i).getFundId()).orElseThrow();
            orders.save(PendingOrder.buy(tenantId, topUp.getPolicyNumber(), fund.getFundId(), parts.get(i),
                PendingOrder.Purpose.ALLOCATION, receivedAt, fund.getCutOffTime(), TopUps.SOURCE, ref));
        }
    }

    /** The premium's last order priced: publish the whole premium once, for the ledger to post as one entry. */
    @Override
    public void afterPriced(PendingOrder order, UnitEntry entry) {
        if (order.getPurpose() != PendingOrder.Purpose.ALLOCATION) {
            return;
        }
        UUID tenantId = TenantContext.get();
        if (orders.countWaiting(tenantId, order.getSourceType(), order.getSourceRef()) > 0) {
            return;
        }
        List<UnitEntry> written = entries.findByTenantIdAndSourceTypeAndSourceRef(tenantId, order.getSourceType(), order.getSourceRef());
        BigDecimal toUnits = written.stream().filter(e -> e.getType() == UnitEntry.Type.ALLOCATION)
            .map(UnitEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal charge = written.stream().filter(e -> e.getType() == UnitEntry.Type.ALLOCATION_CHARGE)
            .map(e -> e.getAmount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
        Fund fund = funds.findByTenantIdAndFundId(tenantId, order.getFundId()).orElseThrow();
        Map<String, Object> payload = new HashMap<>();
        payload.put("policyNumber", order.getPolicyNumber());
        payload.put("sourceRef", order.getSourceRef());
        payload.put("premium", toUnits.add(charge).toPlainString());
        payload.put("allocated", toUnits.toPlainString());
        payload.put("allocationCharge", charge.toPlainString());
        payload.put("currencyCode", fund.getCurrency());
        payload.put("policyholderPartyId", policyApi.getPolicy(order.getPolicyNumber()).policyholderPartyId().toString());
        // The first premium ever to buy units on this policy -- the one the customer is told about.
        long premiumsInvested = entries.findByTenantIdAndPolicyNumberOrderByCreatedAt(tenantId, order.getPolicyNumber()).stream()
            .filter(e -> e.getType() == UnitEntry.Type.ALLOCATION).map(UnitEntry::getSourceRef).distinct().count();
        payload.put("firstPremium", premiumsInvested == 1);
        events.publishEvent(DomainEventEnvelope.of("unitlinked.UnitsAllocated", tenantId, payload));
    }
}
