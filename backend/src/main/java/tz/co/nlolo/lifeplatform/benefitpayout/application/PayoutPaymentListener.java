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

    /** The four purposes that close an instalment. A free-look refund closes a cancellation. */
    static final Set<String> INSTALMENT_PURPOSES = Set.of(
        "MATURITY_PAYOUT", "SURVIVAL_BENEFIT_PAYOUT", "INCOME_PAYOUT", "PREMIUM_RETURN_PAYOUT");

    private final BenefitPayoutApiImpl api;
    private final PolicyEventListener tenantRunner;

    public PayoutPaymentListener(BenefitPayoutApiImpl api, PolicyEventListener tenantRunner) {
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
        if (!INSTALMENT_PURPOSES.contains((String) payload.get("purpose"))) {
            return;
        }
        UUID instalmentId = UUID.fromString((String) payload.get("sourceRef"));

        tenantRunner.withTenant(envelope, p -> {
            if (completed) {
                Object idObj = p.get("disbursementId");
                UUID disbursementId = idObj instanceof UUID u ? u : UUID.fromString((String) idObj);
                api.markPaid(instalmentId, disbursementId);
            } else {
                api.markFailed(instalmentId);
            }
        });
    }
}
