package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/**
 * {@code billing.PremiumCollected}, read by its payload keys {@code policyNumber},
 * {@code amount.amount} and {@code paidToDate}.
 *
 * <p>{@code paidToDate} is billing's CONTIGUOUS paid-to date, which is exactly what the arrears
 * hold needs: a policy that paid this month but missed an earlier one is genuinely behind.
 */
@Component("benefitpayoutPremiumEventListener")
public class PremiumEventListener {

    private final BenefitPayoutApiImpl api;
    private final PolicyEventListener tenantRunner;

    public PremiumEventListener(BenefitPayoutApiImpl api, PolicyEventListener tenantRunner) {
        this.api = api;
        this.tenantRunner = tenantRunner;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        tenantRunner.withTenant(envelope, p -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> amount = (Map<String, Object>) p.get("amount");
            api.recordPremium((String) p.get("policyNumber"), new BigDecimal((String) amount.get("amount")),
                p.get("paidToDate") != null ? LocalDate.parse((String) p.get("paidToDate")) : null);
        });
    }
}
