package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Closes out a payout when its disbursement returns -- {@code SurrenderPaymentListener}'s shape.
 *
 * <p>An IN_DOUBT disbursement publishes NEITHER event: payment holds it for reconciliation, so the
 * instalment simply stays APPROVED until a person establishes whether the money moved. That is
 * deliberate. A blind retry on a payout that may already have landed pays the customer twice, and
 * the mobile-money rail cannot always tell us which happened.
 */
@Component("benefitpayoutPaymentListener")
public class PayoutPaymentListener {

    /** The purposes that close an instalment (ANNUITY_PAYOUT since product step 5). A free-look refund closes a cancellation. */
    static final Set<String> INSTALMENT_PURPOSES = Set.of(
        "MATURITY_PAYOUT", "SURVIVAL_BENEFIT_PAYOUT", "INCOME_PAYOUT", "PREMIUM_RETURN_PAYOUT", "ANNUITY_PAYOUT",
        // A pension's lump sum at vesting (D2).
        "COMMUTATION_PAYOUT");

    static final String FREE_LOOK_PURPOSE = "FREE_LOOK_REFUND";

    private final BenefitPayoutApiImpl api;
    private final EnvelopeRunner tenantRunner;

    public PayoutPaymentListener(BenefitPayoutApiImpl api, EnvelopeRunner tenantRunner) {
        this.api = api;
        this.tenantRunner = tenantRunner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        boolean completed = "payment.DisbursementCompleted".equals(type);
        if (!completed && !"payment.DisbursementFailed".equals(type)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        String purpose = (String) payload.get("purpose");
        boolean freeLook = FREE_LOOK_PURPOSE.equals(purpose);
        if (!freeLook && !INSTALMENT_PURPOSES.contains(purpose)) {
            return;
        }
        // Both carry their own id in sourceRef -- an instalment's for a payout, a cancellation's
        // for a refund -- which is why one listener can close out either.
        UUID sourceRef = UUID.fromString((String) payload.get("sourceRef"));

        tenantRunner.run(envelope, p -> {
            if (completed) {
                Object idObj = p.get("disbursementId");
                UUID disbursementId = idObj instanceof UUID u ? u : UUID.fromString((String) idObj);
                if (freeLook) {
                    api.markFreeLookRefunded(sourceRef, disbursementId);
                } else {
                    api.markPaid(sourceRef, disbursementId);
                }
            } else if (freeLook) {
                api.markFreeLookRefundFailed(sourceRef);
            } else {
                api.markFailed(sourceRef);
            }
        });
    }
}
