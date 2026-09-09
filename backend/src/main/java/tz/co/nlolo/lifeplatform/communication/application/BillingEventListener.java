package tz.co.nlolo.lifeplatform.communication.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tells a customer their payment arrived.
 *
 * <p><b>Its own listener rather than a fifth message inside the policy one, and that separation is
 * load-bearing.</b> Cover starts once; premiums are paid every month. The activation path is
 * deliberately silent on an already-ACTIVE policy, because re-publishing
 * {@code policy.PolicyActivated} would double-accrue the agent's commission and double-cede the
 * risk to the reinsurer — so a receipt could not be bolted onto it without either breaking that
 * guard or firing only once, which is the hole this closes.
 *
 * <p>Fires on every collected premium INCLUDING the first, so a customer starting cover receives
 * both "payment received" and "you are covered". Two true and different facts: one confirms the
 * money, the other confirms they are insured, and collapsing them would lose the half that
 * matters on every subsequent month.
 *
 * <p>{@code billing.PremiumCollected} fires only when an invoice actually reaches PAID — a partial
 * payment does not qualify — so this cannot thank somebody for money that has not fully arrived.
 *
 * <p>Bean name is explicit: {@code finaccounting} and {@code regreporting} each already declare a
 * {@code BillingEventListener}, and a third unqualified one is a bean-name collision that fails
 * context startup for the whole suite.
 */
@Component("communicationBillingEventListener")
public class BillingEventListener {

    private static final Logger log = LoggerFactory.getLogger(BillingEventListener.class);

    private final NotificationApi notificationApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public BillingEventListener(NotificationApi notificationApi,
                                 PlatformTransactionManager transactionManager) {
        this.notificationApi = notificationApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"billing.PremiumCollected".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();

        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"),
                    (String) payload.get("policyNumber"), "PAYMENT_RECEIVED",
                    Map.of(
                        "policyNumber", (String) payload.get("policyNumber"),
                        "amount", money(payload.get("amount")))));
        } catch (Exception e) {
            // The money is collected either way -- billing has committed, and rolling that back to
            // save a receipt would be exactly backwards. Logged loudly; the dispatch row records
            // the failure for the desk.
            log.error("Failed to acknowledge a premium payment on policy {}",
                payload.get("policyNumber"), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /** "TZS 12,500.00" — grouped, and Locale.ROOT so the message never depends on the sender's JVM. */
    private static String money(Object moneyPayload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) moneyPayload;
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return money.get("currencyCode") + " " + format.format(new BigDecimal((String) money.get("amount")));
    }
}
