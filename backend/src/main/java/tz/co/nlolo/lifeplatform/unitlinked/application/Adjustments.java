package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.unitlinked.api.AdjustmentView;
import tz.co.nlolo.lifeplatform.unitlinked.api.FundNotFoundException;
import tz.co.nlolo.lifeplatform.unitlinked.domain.PriceCorrectionAdjustment;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.PriceCorrectionAdjustmentRepository;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The price-correction adjustment queue (spec §3). Owed to the customer: paid out through payment under
 * PRICE_CORRECTION_PAYOUT, keyed on the adjustment so it is paid once. Owed by them: recorded as collected outside
 * the platform under a reference, or waived with a reason. Always by someone other than whoever approved the
 * correction that raised it.
 */
@Component
class Adjustments {

    /** The idempotency-key prefix of an adjustment payout, and how payment's outcome is recognised as one. */
    static final String KEY_PREFIX = "price-correction:";

    private final PriceCorrectionAdjustmentRepository adjustments;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    Adjustments(PriceCorrectionAdjustmentRepository adjustments, PolicyApi policyApi, ApplicationEventPublisher events,
                @Qualifier("unitLinkedClock") Clock clock) {
        this.adjustments = adjustments;
        this.policyApi = policyApi;
        this.events = events;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    List<AdjustmentView> list(String status) {
        UUID tenantId = TenantContext.get();
        List<PriceCorrectionAdjustment> found = status == null || status.isBlank()
            ? adjustments.findByTenantIdOrderByPolicyNumber(tenantId)
            : adjustments.findByTenantIdAndStatusOrderByPolicyNumber(tenantId, status.trim().toUpperCase());
        return found.stream().map(Adjustments::view).toList();
    }

    @Transactional
    AdjustmentView settle(UUID adjustmentId, String payeeRef, String by) {
        if (payeeRef == null || payeeRef.isBlank()) {
            throw new IllegalArgumentException("Settling an adjustment needs the payee to pay, or the reference it was collected under");
        }
        PriceCorrectionAdjustment a = load(adjustmentId);
        a.settle(by, payeeRef.trim(), clock.instant());
        adjustments.save(a);
        if (a.getDirection() == PriceCorrectionAdjustment.Direction.OWED_TO_CUSTOMER) {
            String currency = policyApi.getPolicy(a.getPolicyNumber()).premiumCurrency();
            events.publishEvent(DomainEventEnvelope.of("unitlinked.PayoutRequested", TenantContext.get(), Map.of(
                "purpose", "PRICE_CORRECTION_PAYOUT",
                "sourceRef", a.getAdjustmentId().toString(),
                "idempotencyKey", KEY_PREFIX + a.getAdjustmentId(),
                "policyNumber", a.getPolicyNumber(),
                "payeeRef", payeeRef.trim(),
                "amount", Map.of("amount", a.getAmount().toPlainString(), "currencyCode", currency))));
        } else {
            // Owed by the customer and collected outside the platform: the receivable the correction raised is cleared.
            decided("unitlinked.AdjustmentCollected", a);
        }
        return view(a);
    }

    @Transactional
    AdjustmentView waive(UUID adjustmentId, String reason, String by) {
        PriceCorrectionAdjustment a = load(adjustmentId);
        a.waive(by, reason, clock.instant());
        adjustments.save(a);
        // What the correction left owed (2130) or owing (1230) is released against benefits (5100).
        decided("unitlinked.AdjustmentWaived", a);
        return view(a);
    }

    /** payment paid an adjustment owed to the customer: the cash leg, booked against what the correction left owed. */
    @Transactional(readOnly = true)
    void onPaid(PaymentEventListener.Paid paid) {
        if (!"PRICE_CORRECTION_PAYOUT".equals(paid.purpose())) {
            return;
        }
        adjustments.findByTenantIdAndAdjustmentId(TenantContext.get(), UUID.fromString(paid.sourceRef())).ifPresent(a ->
            // Keyed on the disbursement, so a redelivered payment is refused by the ledger's (event, source) key.
            events.publishEvent(DomainEventEnvelope.of("unitlinked.PayoutPaid", TenantContext.get(),
                paid.payload(a.getPolicyNumber()))));
    }

    private void decided(String type, PriceCorrectionAdjustment a) {
        String currency = policyApi.getPolicy(a.getPolicyNumber()).premiumCurrency();
        events.publishEvent(DomainEventEnvelope.of(type, TenantContext.get(), Map.of(
            "sourceRef", a.getAdjustmentId().toString(), "policyNumber", a.getPolicyNumber(),
            "direction", a.getDirection().name(), "amount", a.getAmount().toPlainString(), "currencyCode", currency)));
    }

    private PriceCorrectionAdjustment load(UUID adjustmentId) {
        return adjustments.findByTenantIdAndAdjustmentId(TenantContext.get(), adjustmentId)
            .orElseThrow(() -> new FundNotFoundException("Adjustment " + adjustmentId));
    }

    static AdjustmentView view(PriceCorrectionAdjustment a) {
        return new AdjustmentView(a.getAdjustmentId(), a.getPolicyNumber(), a.getCorrectedPriceId(), a.getAmount(),
            a.getDirection().name(), a.getStatus(), a.getProposedBy(), a.getDecidedBy(), a.getDecidedAt(), a.getReason());
    }
}
