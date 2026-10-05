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
import tz.co.nlolo.lifeplatform.benefitpayout.api.BenefitPayoutApi;
import tz.co.nlolo.lifeplatform.party.api.PartyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.product.api.ProductApi;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan;
import tz.co.nlolo.lifeplatform.unitlinked.api.DeathValueView;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitLinkedStateException;
import tz.co.nlolo.lifeplatform.unitlinked.api.UnitsNotYetPricedException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import tz.co.nlolo.lifeplatform.unitlinked.domain.ExitState;
import tz.co.nlolo.lifeplatform.unitlinked.domain.FrozenPolicy;
import tz.co.nlolo.lifeplatform.unitlinked.domain.Fund;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PendingOrder;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PolicyAllocation;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitArithmetic;
import tz.co.nlolo.lifeplatform.unitlinked.domain.UnitEntry;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.ExitStateRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.FundRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PendingOrderRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PolicyAllocationRepository;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.UnitEntryRepository;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every way out of a unit-linked policy (spec §7): a death, a surrender, a maturity, a lapse for non-payment, and a
 * free-look cancellation. Each one the same way -- freeze the policy, return any premium not yet in units, cancel
 * waiting charges, and sell every unit FORWARD from the instant that triggered it -- and each finishes in its own
 * words once the last of its sales is priced: claims pays a death; payment pays a surrender, maturity or lapse;
 * benefitpayout pays a free-look refund. Every exit is idempotent on its source.
 */
@Component
class Exits implements UnitsPricedListener {

    private static final Logger log = LoggerFactory.getLogger(Exits.class);

    static final String DEATH = "claim";
    static final String SURRENDER = "surrender";
    static final String MATURITY = "maturity";
    static final String LAPSE = "lapse";
    static final String FREE_LOOK = "freelook";

    private final PendingOrderRepository orders;
    private final UnitEntryRepository entries;
    private final ExitStateRepository exits;
    private final FundRepository funds;
    private final PolicyAllocationRepository allocations;
    private final PolicyApi policyApi;
    private final ProductApi productApi;
    private final PartyApi partyApi;
    private final BenefitPayoutApi benefitPayoutApi;
    private final UnitLedger ledger;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    Exits(PendingOrderRepository orders, UnitEntryRepository entries, ExitStateRepository exits, FundRepository funds,
          PolicyAllocationRepository allocations, PolicyApi policyApi, ProductApi productApi, PartyApi partyApi,
          BenefitPayoutApi benefitPayoutApi, @Lazy UnitLedger ledger, ApplicationEventPublisher events,
          @Qualifier("unitLinkedClock") Clock clock) {
        this.orders = orders;
        this.entries = entries;
        this.exits = exits;
        this.funds = funds;
        this.allocations = allocations;
        this.policyApi = policyApi;
        this.productApi = productApi;
        this.partyApi = partyApi;
        this.benefitPayoutApi = benefitPayoutApi;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    /**
     * The policy's CATEGORY first, so an event on any other policy never touches this module's tables -- a test
     * context that never sells a unit-linked policy need not migrate them (the funeral trap).
     */
    boolean isUnitLinked(String policyNumber) {
        if (policyNumber == null) {
            return false;
        }
        try {
            if (!"UNIT_LINKED".equals(policyApi.getPolicy(policyNumber).productCategory())) {
                return false;
            }
        } catch (tz.co.nlolo.lifeplatform.policy.api.PolicyNotFoundException e) {
            return false; // an event about a policy that is not this tenant's, or not there at all
        }
        return allocations.existsByTenantIdAndPolicyNumber(TenantContext.get(), policyNumber);
    }

    // ---- The shared exit -------------------------------------------------------------------------------------

    /**
     * Freezes the policy, returns waiting premiums, cancels waiting charges, and sells every unit forward from
     * {@code at}. With nothing to sell, the exit is complete at once.
     */
    private void exit(String policyNumber, String purpose, FrozenPolicy.Reason reason, String sourceType, String sourceRef,
                      Instant at, String payeeRef) {
        UUID tenantId = TenantContext.get();
        if (exits.findByTenantIdAndSourceTypeAndSourceRef(tenantId, sourceType, sourceRef).isPresent()) {
            return; // redelivered
        }
        ledger.freeze(policyNumber, reason, sourceRef);
        ExitState state = new ExitState(tenantId, policyNumber, purpose, sourceType, sourceRef, payeeRef);
        java.util.Set<String> returnedPremiums = new java.util.HashSet<>();
        for (PendingOrder waiting : orders.findByTenantIdAndPolicyNumberAndStatusOrderByReceivedAt(tenantId, policyNumber, "WAITING")) {
            if (waiting.getSide() == PendingOrder.Side.BUY) {
                state.addReturnedMoney(waiting.getAmount()); // a premium never bought units: it goes back as money
                if (Allocations.PREMIUM.equals(waiting.getSourceType())) {
                    returnedPremiums.add(waiting.getSourceRef());
                }
            }
            waiting.cancel();
            orders.save(waiting);
        }
        // A premium that never reached units goes back WHOLE (the user's decision, 2026-10-05): no unit was bought, so
        // no allocation charge is earned. Each charge not already refunded is refunded here, as its own CHARGE_REFUND
        // entry, and returned with the premium. A free-look has already refunded every charge before calling this, so
        // its charges are skipped here and reach the ledger the way they always have: earned on the return, and given
        // back by their ChargeRefunded.
        List<UnitEntry> policyEntries = entries.findByTenantIdAndPolicyNumberOrderByCreatedAt(tenantId, policyNumber);
        java.util.Set<UUID> refunded = policyEntries.stream().filter(e -> e.getType() == UnitEntry.Type.CHARGE_REFUND)
            .map(UnitEntry::getReversesEntryId).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet());
        String currency = policyApi.getPolicy(policyNumber).premiumCurrency();
        for (String premium : returnedPremiums) {
            BigDecimal earned = BigDecimal.ZERO;
            BigDecimal refundedNow = BigDecimal.ZERO;
            for (UnitEntry charge : entries.findByTenantIdAndSourceTypeAndSourceRef(tenantId, Allocations.PREMIUM, premium)) {
                if (charge.getType() != UnitEntry.Type.ALLOCATION_CHARGE) {
                    continue;
                }
                BigDecimal amount = charge.getAmount().negate();
                if (refunded.contains(charge.getEntryId())) {
                    earned = earned.add(amount);
                } else {
                    entries.save(UnitEntry.money(tenantId, policyNumber, UnitEntry.Type.CHARGE_REFUND, amount,
                        BindingRule.civilDate(at), sourceType, sourceRef + ":premium-refund:" + charge.getEntryId(),
                        charge.getEntryId(), UnitLedger.SYSTEM, clock.instant()));
                    refundedNow = refundedNow.add(amount);
                }
            }
            state.addReturnedMoney(refundedNow);
            BigDecimal returned = orders.findByTenantIdAndSourceTypeAndSourceRef(tenantId, Allocations.PREMIUM, premium).stream()
                .map(PendingOrder::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add).add(refundedNow);
            // DR 2140 the whole premium / CR 5100 what goes back / CR 4310 only a charge refunded elsewhere (free-look).
            events.publishEvent(DomainEventEnvelope.of("unitlinked.PremiumReturned", tenantId, Map.of(
                "policyNumber", policyNumber, "sourceRef", premium, "returned", returned.toPlainString(),
                "allocationCharge", earned.toPlainString(), "currencyCode", currency)));
        }
        exits.saveAndFlush(state);
        boolean selling = false;
        for (var held : ledger.holdings(policyNumber).entrySet()) {
            if (held.getValue().signum() <= 0) {
                continue;
            }
            Fund fund = funds.findByTenantIdAndFundId(tenantId, held.getKey()).orElseThrow();
            orders.save(PendingOrder.sellAll(tenantId, policyNumber, fund.getFundId(), PendingOrder.Purpose.valueOf(purpose),
                at, fund.getCutOffTime(), sourceType, sourceRef));
            selling = true;
        }
        if (!selling) {
            complete(state);
        }
    }

    /** Each sale adds its proceeds to its exit; the exit's last one completes it. */
    @Override
    public void afterPriced(PendingOrder order, UnitEntry entry) {
        PendingOrder.Purpose purpose = order.getPurpose();
        if (purpose == PendingOrder.Purpose.REINVESTMENT) {
            // A rejected death's money back into units: the liability takes it back from the exit's expense.
            if (entry != null) {
                events.publishEvent(DomainEventEnvelope.of("unitlinked.UnitsReinvested", TenantContext.get(), Map.of(
                    "policyNumber", order.getPolicyNumber(), "sourceRef", order.getSourceRef() + ":" + order.getFundId(),
                    "amount", entry.getAmount().toPlainString(),
                    "currencyCode", funds.findByTenantIdAndFundId(TenantContext.get(), order.getFundId()).orElseThrow().getCurrency())));
            }
            return;
        }
        if (purpose == PendingOrder.Purpose.ALLOCATION || purpose == PendingOrder.Purpose.CHARGES) {
            return;
        }
        UUID tenantId = TenantContext.get();
        ExitState state = exits.findByTenantIdAndSourceTypeAndSourceRef(tenantId, order.getSourceType(), order.getSourceRef())
            .orElseThrow(() -> new IllegalStateException("A " + purpose + " sale has no exit (" + order.getSourceRef() + ")"));
        if (entry != null) {
            state.addProceeds(entry.getAmount().negate());
        }
        exits.save(state);
        if (orders.countWaiting(tenantId, order.getSourceType(), order.getSourceRef()) == 0) {
            complete(state);
        }
    }

    private void complete(ExitState state) {
        state.priced(clock.instant());
        exits.save(state);
        // The units' proceeds leave the liability against the exit's expense, whoever then pays it out.
        if (state.getProceeds().signum() > 0) {
            events.publishEvent(DomainEventEnvelope.of("unitlinked.ExitPriced", TenantContext.get(), Map.of(
                "policyNumber", state.getPolicyNumber(), "purpose", state.getPurpose(),
                "sourceRef", state.getSourceType() + ":" + state.getSourceRef(), "proceeds", state.getProceeds().toPlainString(),
                "currencyCode", policyApi.getPolicy(state.getPolicyNumber()).premiumCurrency())));
        }
        switch (state.getPurpose()) {
            case "DEATH" -> { /* claims reads deathValue and pays through its own settlement */ }
            case "SURRENDER" -> payOut(state, "SURRENDER_PAYOUT");
            case "MATURITY" -> payOut(state, "MATURITY_PAYOUT");
            case "LAPSE" -> payOut(state, "LAPSE_SURRENDER_PAYOUT");
            case "FREE_LOOK" -> releaseFreeLookRefund(state);
            default -> throw new IllegalStateException("Unknown exit purpose " + state.getPurpose());
        }
    }

    /** Proceeds plus returned money to the payee, through payment; or waiting for staff to name a payee. */
    private void payOut(ExitState state, String paymentPurpose) {
        BigDecimal amount = state.getProceeds().add(state.getReturnedMoney());
        PolicyView policy = policyApi.getPolicy(state.getPolicyNumber());
        if (amount.signum() <= 0) {
            log.warn("Unit-linked {} of {} raised nothing; no payment requested", state.getPurpose(), state.getPolicyNumber());
            return;
        }
        if (state.getPayeeRef() == null || state.getPayeeRef().isBlank()) {
            state.awaitingPayee();
            exits.save(state);
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("purpose", paymentPurpose);
        payload.put("sourceRef", state.getSourceRef());
        // A unit-linked surrender is paid only from here (policy publishes UnitLinkedSurrenderApproved, never its own
        // payout), so one key per exit is enough -- and the prefix is how this module recognises its own payments.
        payload.put("idempotencyKey", "unit-linked:" + state.getSourceType() + ":" + state.getSourceRef());
        payload.put("policyNumber", state.getPolicyNumber());
        payload.put("payeeRef", state.getPayeeRef());
        payload.put("amount", Map.of("amount", amount.toPlainString(), "currencyCode", policy.premiumCurrency()));
        events.publishEvent(DomainEventEnvelope.of("unitlinked.PayoutRequested", TenantContext.get(), payload));
        events.publishEvent(DomainEventEnvelope.of("unitlinked.ProceedsReady", TenantContext.get(), Map.of(
            "policyNumber", state.getPolicyNumber(), "policyholderPartyId", policy.policyholderPartyId().toString(),
            "purpose", state.getPurpose(), "amount", amount.toPlainString(), "currencyCode", policy.premiumCurrency())));
    }

    // ---- Death -----------------------------------------------------------------------------------------------

    /** Registration freezes the policy and sells its units at the first price after it -- never one already known. */
    @Transactional
    void onDeathRegistered(UUID claimId, String policyNumber, Instant registeredAt) {
        exit(policyNumber, "DEATH", FrozenPolicy.Reason.DEATH, DEATH, claimId.toString(), registeredAt, null);
    }

    @Transactional(readOnly = true)
    DeathValueView deathValue(UUID claimId, LocalDate dateOfDeath) {
        UUID tenantId = TenantContext.get();
        ExitState state = exits.findByTenantIdAndSourceTypeAndSourceRef(tenantId, DEATH, claimId.toString())
            .orElseThrow(() -> new UnitLinkedStateException("Claim " + claimId + " froze no units"));
        if (state.getStatus() == ExitState.Status.OPEN) {
            List<PendingOrder> waiting = orders.findByTenantIdAndSourceTypeAndSourceRef(tenantId, DEATH, claimId.toString())
                .stream().filter(PendingOrder::isWaiting).toList();
            List<String> codes = waiting.stream().map(o -> funds.findByTenantIdAndFundId(tenantId, o.getFundId()).orElseThrow().getCode())
                .sorted().toList();
            throw new UnitsNotYetPricedException(codes, waiting.stream().map(PendingOrder::getBoundDate)
                .max(Comparator.naturalOrder()).orElse(null));
        }
        PolicyView policy = policyApi.getPolicy(state.getPolicyNumber());
        UnitLinkedPlan plan = productApi.resolveUnitLinkedPlan(policy.productVersionId());
        BigDecimal proceeds = state.getProceeds().add(state.getReturnedMoney());
        // Every cost of insurance dated after the death: the life was not insured by then, so it is given back.
        BigDecimal refund = entries.findByTenantIdAndPolicyNumberOrderByCreatedAt(tenantId, state.getPolicyNumber()).stream()
            .filter(e -> e.getType() == UnitEntry.Type.COST_OF_INSURANCE && e.getValuationDate().isAfter(dateOfDeath))
            .map(e -> e.getAmount().negate()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumAssured = policy.sumAssuredAmount();
        BigDecimal benefit = plan.deathRule() == UnitLinkedPlan.DeathRule.HIGHER_OF
            ? sumAssured.max(proceeds).add(refund)
            : sumAssured.add(proceeds).add(refund);
        return new DeathValueView(benefit, proceeds, refund, sumAssured, plan.deathRule().name(), policy.premiumCurrency());
    }

    /** The claim is decided to pay: record the cost-of-insurance refund once, and mark the exit paid. */
    @Transactional
    void onDeathApproved(UUID claimId, LocalDate dateOfDeath) {
        UUID tenantId = TenantContext.get();
        exits.findByTenantIdAndSourceTypeAndSourceRef(tenantId, DEATH, claimId.toString()).ifPresent(state -> {
            if (state.getStatus() == ExitState.Status.PAID) {
                return;
            }
            DeathValueView value = deathValue(claimId, dateOfDeath);
            if (value.costOfInsuranceRefund().signum() > 0) {
                entries.save(UnitEntry.money(tenantId, state.getPolicyNumber(), UnitEntry.Type.CHARGE_REFUND,
                    value.costOfInsuranceRefund(), dateOfDeath, DEATH, claimId + ":coi-refund", null, UnitLedger.SYSTEM,
                    clock.instant()));
                chargeRefunded(state.getPolicyNumber(), DEATH + ":" + claimId + ":coi-refund", value.costOfInsuranceRefund());
            }
            state.paid(clock.instant());
            exits.save(state);
        });
    }

    /** A rejected death: the money goes back into units at the next price, by the policy's split, and charges resume. */
    @Transactional
    void onDeathRejected(UUID claimId, Instant rejectedAt) {
        UUID tenantId = TenantContext.get();
        Optional<ExitState> found = exits.findByTenantIdAndSourceTypeAndSourceRef(tenantId, DEATH, claimId.toString());
        if (found.isEmpty() || found.get().getStatus() == ExitState.Status.REVERSED) {
            return;
        }
        ExitState state = found.get();
        String policyNumber = state.getPolicyNumber();
        // Sales still waiting are simply cancelled: the units never left.
        for (PendingOrder o : orders.findByTenantIdAndSourceTypeAndSourceRef(tenantId, DEATH, claimId.toString())) {
            if (o.isWaiting()) {
                o.cancel();
                orders.save(o);
            }
        }
        BigDecimal back = state.getProceeds().add(state.getReturnedMoney());
        if (back.signum() > 0) {
            List<PolicyAllocation> split = allocations.findByTenantIdAndPolicyNumber(tenantId, policyNumber).stream()
                .sorted(Comparator.comparing(a -> a.getFundId().toString())).toList();
            List<BigDecimal> parts = UnitArithmetic.split(back, split.stream()
                .map(a -> new UnitArithmetic.Weighted(a.getFundId().toString(), BigDecimal.valueOf(a.getPercent()))).toList());
            for (int i = 0; i < split.size(); i++) {
                if (parts.get(i).signum() <= 0) {
                    continue;
                }
                Fund fund = funds.findByTenantIdAndFundId(tenantId, split.get(i).getFundId()).orElseThrow();
                orders.save(PendingOrder.buy(tenantId, policyNumber, fund.getFundId(), parts.get(i),
                    PendingOrder.Purpose.REINVESTMENT, rejectedAt, fund.getCutOffTime(), "claim-rejected", claimId.toString()));
            }
        }
        state.reversed(clock.instant());
        exits.save(state);
        ledger.unfreeze(policyNumber);
    }

    // ---- Surrender, maturity, lapse ----------------------------------------------------------------------------

    /** Bound at APPROVAL, not the request: an approver who could see the price first could choose it (spec §7). */
    @Transactional
    void onSurrenderApproved(UUID surrenderRequestId, String policyNumber, String payeeRef, Instant approvedAt) {
        exit(policyNumber, "SURRENDER", FrozenPolicy.Reason.SURRENDER, SURRENDER, surrenderRequestId.toString(), approvedAt,
            payeeRef);
    }

    /** The maturity date's own price: the sweep binds the sale to that date, before its cut-off. */
    @Transactional
    void mature(String policyNumber, LocalDate maturityDate) {
        Instant at = maturityDate.atTime(LocalTime.MIDNIGHT).atZone(BindingRule.CIVIL_ZONE).toInstant();
        exit(policyNumber, "MATURITY", FrozenPolicy.Reason.MATURITY, MATURITY, policyNumber, at, payeeOf(policyNumber));
    }

    /** Lapsed for non-payment: the units are sold forward and paid to the policyholder as a lapse surrender value. */
    @Transactional
    void lapse(String policyNumber, Instant lapsedAt) {
        exit(policyNumber, "LAPSE", FrozenPolicy.Reason.LAPSE, LAPSE, policyNumber + ":" + lapsedAt, lapsedAt,
            payeeOf(policyNumber));
    }

    /** The policyholder's own mobile number, the platform's mobile-money payee; none means staff name one. */
    private String payeeOf(String policyNumber) {
        try {
            String phone = partyApi.getPartyDetail(policyApi.getPolicy(policyNumber).policyholderPartyId()).phoneNumber();
            return phone == null || phone.isBlank() ? null : phone;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Transactional
    void payAwaiting(String policyNumber, String payeeRef) {
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new IllegalArgumentException("A payee reference is required");
        }
        ExitState state = exits.findByTenantIdAndPolicyNumber(TenantContext.get(), policyNumber).stream()
            .filter(e -> e.getStatus() == ExitState.Status.AWAITING_PAYEE).findFirst()
            .orElseThrow(() -> new UnitLinkedStateException("Policy " + policyNumber + " has no payout waiting for a payee"));
        state.payTo(payeeRef.trim());
        state.priced(clock.instant());
        exits.save(state);
        payOut(state, "MATURITY".equals(state.getPurpose()) ? "MATURITY_PAYOUT" : "LAPSE_SURRENDER_PAYOUT");
    }

    /**
     * payment's outcome for a payout this module asked for: the exit is paid, a maturity closes the policy, and the
     * cash leg is published -- once, on the transition to PAID, so a redelivered payment books nothing twice.
     */
    @Transactional
    void onPaid(PaymentEventListener.Paid paid) {
        UUID tenantId = TenantContext.get();
        String purpose = paid.purpose();
        String sourceRef = paid.sourceRef();
        String sourceType = switch (purpose) {
            case "SURRENDER_PAYOUT" -> SURRENDER;
            case "MATURITY_PAYOUT" -> MATURITY;
            case "LAPSE_SURRENDER_PAYOUT" -> LAPSE;
            default -> null;
        };
        if (sourceType == null) {
            return;
        }
        exits.findByTenantIdAndSourceTypeAndSourceRef(tenantId, sourceType, sourceRef).ifPresent(state -> {
            if (state.getStatus() == ExitState.Status.PAID) {
                return;
            }
            state.paid(clock.instant());
            exits.save(state);
            events.publishEvent(DomainEventEnvelope.of("unitlinked.PayoutPaid", tenantId, paid.payload(state.getPolicyNumber())));
            if (MATURITY.equals(sourceType)) {
                policyApi.markMatured(state.getPolicyNumber(), UnitLedger.SYSTEM);
            }
        });
    }

    // ---- Free-look ----------------------------------------------------------------------------------------------

    /**
     * The actual entries unwound, not a formula (spec Q12): each charge the policy took is refunded entry by entry,
     * and every unit it holds is sold forward. The refund is those, plus any premium still waiting for units.
     */
    @Transactional
    void onFreeLookCancelled(String policyNumber, Instant cancelledAt) {
        UUID tenantId = TenantContext.get();
        if (exits.findByTenantIdAndSourceTypeAndSourceRef(tenantId, FREE_LOOK, policyNumber).isPresent()) {
            return;
        }
        for (UnitEntry charge : entries.findByTenantIdAndPolicyNumberOrderByCreatedAt(tenantId, policyNumber)) {
            if (charge.getType() == UnitEntry.Type.ALLOCATION_CHARGE || charge.getType() == UnitEntry.Type.POLICY_FEE
                    || charge.getType() == UnitEntry.Type.COST_OF_INSURANCE) {
                entries.save(UnitEntry.money(tenantId, policyNumber, UnitEntry.Type.CHARGE_REFUND, charge.getAmount().negate(),
                    BindingRule.civilDate(cancelledAt), FREE_LOOK, policyNumber + ":refund:" + charge.getEntryId(),
                    charge.getEntryId(), UnitLedger.SYSTEM, clock.instant()));
                chargeRefunded(policyNumber, FREE_LOOK + ":" + policyNumber + ":refund:" + charge.getEntryId(), charge.getAmount().negate());
            }
        }
        exit(policyNumber, "FREE_LOOK", FrozenPolicy.Reason.FREE_LOOK, FREE_LOOK, policyNumber, cancelledAt, null);
    }

    /** A charge given back: the income it was is reversed against the payout that returns it. */
    private void chargeRefunded(String policyNumber, String sourceRef, BigDecimal amount) {
        events.publishEvent(DomainEventEnvelope.of("unitlinked.ChargeRefunded", TenantContext.get(), Map.of(
            "policyNumber", policyNumber, "sourceRef", sourceRef, "amount", amount.toPlainString(),
            "currencyCode", policyApi.getPolicy(policyNumber).premiumCurrency())));
    }

    private void releaseFreeLookRefund(ExitState state) {
        UUID tenantId = TenantContext.get();
        BigDecimal refunded = entries.findByTenantIdAndPolicyNumberOrderByCreatedAt(tenantId, state.getPolicyNumber()).stream()
            .filter(e -> e.getType() == UnitEntry.Type.CHARGE_REFUND && FREE_LOOK.equals(e.getSourceType()))
            .map(UnitEntry::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refund = state.getProceeds().add(state.getReturnedMoney()).add(refunded);
        benefitPayoutApi.findFreeLook(state.getPolicyNumber()).ifPresentOrElse(
            c -> benefitPayoutApi.releaseUnitLinkedFreeLookRefund(c.cancellationId(), refund),
            () -> log.error("Unit-linked policy {} was cancelled in free-look with no cancellation to refund through",
                state.getPolicyNumber()));
        // benefitpayout owns the refund from here (its paid/failed marking): the unwind itself is complete.
        state.paid(clock.instant());
        exits.save(state);
    }
}
