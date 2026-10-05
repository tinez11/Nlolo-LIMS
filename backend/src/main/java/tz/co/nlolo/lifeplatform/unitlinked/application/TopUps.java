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
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundNotFoundException;
import tz.co.nlolo.lifeplatform.unitlinked.api.PremiumSplitView;
import tz.co.nlolo.lifeplatform.unitlinked.api.TopUpInput;
import tz.co.nlolo.lifeplatform.unitlinked.api.TopUpView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.ExitState;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PremiumSplit;
import tz.co.nlolo.lifeplatform.unitlinked.domain.TopUp;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.ExitStateRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.TopUpRepository;


import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Top-ups (U2, spec §4). Requested once per Idempotency-Key -- a retry never collects or credits twice -- and collected
 * through payment under UL_TOP_UP, a purpose billing and accumulation both ignore. When payment confirms the money it
 * is allocated at the version's own top-up percent by its own split or the one in force, bound by the instant it was
 * received; a policy frozen or ended by then never takes it: an exit still open carries it back with its other money,
 * one already finished has it refunded whole.
 */
@Component
class TopUps {

    static final String SOURCE = "top-up";
    static final String PURPOSE = "UL_TOP_UP";
    /** Its own disbursement purpose: benefitpayout claims every PREMIUM_RETURN_PAYOUT as one of its instalments. */
    static final String REFUND_PURPOSE = "TOP_UP_REFUND";
    private static final Set<String> IN_FORCE = Set.of("ACTIVE", "REINSTATED");

    private final TopUpRepository topUps;
    private final ExitStateRepository exits;
    private final FundRepository funds;
    private final UnitLedger ledger;
    private final Allocations allocations;
    private final IdempotentRequests keyed;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    TopUps(TopUpRepository topUps, ExitStateRepository exits, FundRepository funds,
           @org.springframework.context.annotation.Lazy UnitLedger ledger, Allocations allocations, IdempotentRequests keyed,
           PolicyApi policyApi, ProductApi productApi, ApplicationEventPublisher events, @Qualifier("unitLinkedClock") Clock clock) {
        this.topUps = topUps;
        this.exits = exits;
        this.funds = funds;
        this.ledger = ledger;
        this.allocations = allocations;
        this.keyed = keyed;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.events = events;
        this.clock = clock;
    }

    TopUpView request(String policyNumber, TopUpInput input, String by, String idempotencyKey) {
        return keyed.once(idempotencyKey, "TOP_UP", policyNumber, by, () -> create(policyNumber, input, by),
            TopUpView::topUpId, id -> view(load(id)));
    }

    private TopUpView create(String policyNumber, TopUpInput input, String by) {
        UUID tenantId = TenantContext.get();
        PolicyView policy = policyApi.getPolicy(policyNumber);
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        if (!plan.unitLinked() || !plan.options().topUpsOffered()) {
            throw new UnitLinkedStateException("This product does not take top-ups");
        }
        if (!IN_FORCE.contains(policy.status().name()) || ledger.isFrozen(policyNumber)) {
            throw new UnitLinkedStateException("Policy " + policyNumber + " is not in force; it takes no top-up");
        }
        if (input == null || input.amount() == null || input.amount().compareTo(plan.options().minimumTopUp()) < 0) {
            throw new IllegalArgumentException("A top-up is at least " + String.format("%,.2f", plan.options().minimumTopUp())
                + " " + policy.premiumCurrency());
        }
        if (input.payerRef() == null || input.payerRef().isBlank()) {
            throw new IllegalArgumentException("A top-up needs the payer to collect it from");
        }
        List<PremiumSplit.Share> split = input.split() == null || input.split().isEmpty()
            ? List.of() : SplitRules.resolve(input.split(), plan, funds);
        TopUp saved = topUps.save(new TopUp(tenantId, policyNumber, input.amount(), policy.premiumCurrency(),
            input.payerRef().trim(), split, by, clock.instant()));
        Map<String, Object> payload = new HashMap<>();
        payload.put("topUpId", saved.getTopUpId().toString());
        payload.put("idempotencyKey", "ul-topup:" + saved.getTopUpId());
        payload.put("policyNumber", policyNumber);
        payload.put("payerRef", saved.getPayerRef());
        payload.put("amount", Map.of("amount", saved.getAmount().toPlainString(), "currencyCode", saved.getCurrency()));
        events.publishEvent(DomainEventEnvelope.of("unitlinked.TopUpRequested", tenantId, payload));
        return view(saved);
    }

    /** payment confirmed the money (purpose UL_TOP_UP): allocate it, or carry it back if the policy is leaving. */
    @Transactional
    void onConfirmed(UUID topUpId, Instant confirmedAt) {
        UUID tenantId = TenantContext.get();
        TopUp topUp = topUps.findByTenantIdAndTopUpId(tenantId, topUpId).orElse(null);
        if (topUp == null || !topUp.awaitingMoney()) {
            return; // redelivered, or not ours
        }
        String ref = SOURCE + ":" + topUpId;
        // The cash is in: DR cash / CR 2140, where a collected premium sits before it buys units.
        events.publishEvent(DomainEventEnvelope.of("unitlinked.TopUpReceived", tenantId, Map.of(
            "topUpId", topUpId.toString(), "policyNumber", topUp.getPolicyNumber(), "sourceRef", ref,
            "amount", topUp.getAmount().toPlainString(), "currencyCode", topUp.getCurrency())));
        PolicyView policy = policyApi.getPolicy(topUp.getPolicyNumber());
        if (IN_FORCE.contains(policy.status().name()) && !ledger.isFrozen(topUp.getPolicyNumber())) {
            topUp.received(confirmedAt);
            topUps.save(topUp);
            allocations.onTopUpReceived(topUp, confirmedAt);
            return;
        }
        topUp.refunded(confirmedAt);
        topUps.save(topUp);
        ExitState open = exits.findByTenantIdAndPolicyNumber(tenantId, topUp.getPolicyNumber()).stream()
            .filter(e -> e.getStatus() == ExitState.Status.OPEN).findFirst().orElse(null);
        if (open != null) {
            // The exit pays it back with the rest; DR 2140 / CR 5100, as a premium returned unbought.
            open.addReturnedMoney(topUp.getAmount());
            exits.save(open);
            events.publishEvent(DomainEventEnvelope.of("unitlinked.PremiumReturned", tenantId, Map.of(
                "policyNumber", topUp.getPolicyNumber(), "sourceRef", ref, "returned", topUp.getAmount().toPlainString(),
                "allocationCharge", "0", "currencyCode", topUp.getCurrency())));
            return;
        }
        // Nothing open to carry it: refunded whole, to the number it came from. Its PayoutPaid posts DR 2140 / CR cash.
        Map<String, Object> payout = new HashMap<>();
        payout.put("purpose", REFUND_PURPOSE);
        payout.put("sourceRef", topUpId.toString());
        payout.put("idempotencyKey", "unit-linked:" + SOURCE + ":" + topUpId);
        payout.put("policyNumber", topUp.getPolicyNumber());
        payout.put("payeeRef", topUp.getPayerRef());
        payout.put("amount", Map.of("amount", topUp.getAmount().toPlainString(), "currencyCode", topUp.getCurrency()));
        events.publishEvent(DomainEventEnvelope.of("unitlinked.PayoutRequested", tenantId, payout));
    }

    @Transactional
    void onFailed(UUID topUpId) {
        topUps.findByTenantIdAndTopUpId(TenantContext.get(), topUpId).ifPresent(t -> {
            t.failed();
            topUps.save(t);
        });
    }

    /** payment paid a refunded top-up back: the cash leg, keyed on the disbursement. */
    void onRefundPaid(PaymentEventListener.Paid paid) {
        if (!REFUND_PURPOSE.equals(paid.purpose())) {
            return;
        }
        topUps.findByTenantIdAndTopUpId(TenantContext.get(), UUID.fromString(paid.sourceRef())).ifPresent(t ->
            events.publishEvent(DomainEventEnvelope.of("unitlinked.PayoutPaid", TenantContext.get(),
                paid.payload(t.getPolicyNumber()))));
    }

    @Transactional(readOnly = true)
    List<TopUpView> list(String policyNumber) {
        return topUps.findByTenantIdAndPolicyNumberOrderByRequestedAtDesc(TenantContext.get(), policyNumber).stream()
            .map(this::view).toList();
    }

    private TopUp load(UUID topUpId) {
        return topUps.findByTenantIdAndTopUpId(TenantContext.get(), topUpId)
            .orElseThrow(() -> new FundNotFoundException("Top-up " + topUpId));
    }

    private TopUpView view(TopUp t) {
        return new TopUpView(t.getTopUpId(), t.getPolicyNumber(), t.getAmount(), t.getCurrency(), t.getPayerRef(),
            t.getSplit().stream().map(s -> new PremiumSplitView.Share(
                funds.findByTenantIdAndFundId(t.getTenantId(), s.getFundId()).map(Fund::getCode).orElse("?"), s.getPercent()))
                .toList(),
            t.getStatus(), t.getRequestedBy(), t.getRequestedAt(), t.getReceivedAt());
    }
}
