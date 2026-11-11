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
 * Tells a customer their money arrived, under its receipt number.
 *
 * <p>On {@code payment.ReceiptIssued} since receipts step 1 (2026-11-05) -- once per amount received, whatever it is
 * for. Until then it fired on {@code billing.PremiumCollected}, an invoice reaching PAID: money paid ahead or in part
 * paid no invoice, so the customer heard nothing, and since money movement a premium is paid from the balance,
 * not by the money that came in. The receipt is what TIRA's Code B.5(b) asks for: "acknowledge receipt ... of all
 * monies received".
 *
 * <p>Still its own listener rather than a message inside the policy one: cover starts once, money arrives every
 * month. A customer starting cover receives both "payment received" and "you are covered" -- two true and different
 * facts.
 *
 * <p>Bean name is explicit, as its predecessor's was: {@code finaccounting} and {@code regreporting} each declare
 * listeners of their own, and an unqualified name risks a collision that fails context startup for the whole suite.
 */
@Component("communicationReceiptEventListener")
public class ReceiptEventListener {

    private static final Logger log = LoggerFactory.getLogger(ReceiptEventListener.class);

    private final NotificationApi notificationApi;
    private final tz.co.nlolo.lifeplatform.party.api.PartyApi partyApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public ReceiptEventListener(NotificationApi notificationApi, tz.co.nlolo.lifeplatform.party.api.PartyApi partyApi,
                                PlatformTransactionManager transactionManager) {
        this.notificationApi = notificationApi;
        this.partyApi = partyApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = envelope.payload() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String policyNumber = (String) payload.get("policyNumber");
        switch (envelope.eventType()) {
            case "payment.ReceiptIssued" -> tell(envelope, payload, "PAYMENT_RECEIVED", Map.of(
                "policyNumber", policyNumber, "amount", money(payload.get("amount")),
                "receiptNumber", (String) payload.get("receiptNumber")));
            // Cash handed to an agent, acknowledged as the agent records it; its receipt follows once it reaches us.
            // Named (2026-11-11, the user): "Your agent Juma Senior has collected ..." -- cash handed to someone else is
            // caught by the customer at once.
            case "billing.FieldReceiptCaptured" -> tell(envelope, payload, "AGENT_CASH_RECEIVED", Map.of(
                "policyNumber", policyNumber, "amount", money(payload.get("amount")),
                "agentName", agentName(envelope, payload.get("agentPartyId"))));
            // A reminder staff or the customer's agent sent from Premiums to follow up (2026-11-11).
            case "billing.PremiumReminderSent" -> tell(envelope, payload, "PREMIUM_REMINDER", Map.of(
                "policyNumber", policyNumber, "amount", money(payload.get("amount")),
                "dueDate", java.time.LocalDate.parse((String) payload.get("dueDate"))
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))));
            default -> { /* not a receipt */ }
        }
    }

    private void tell(DomainEventEnvelope<?> envelope, Map<String, Object> payload, String templateKey,
                      Map<String, String> values) {
        if (payload.get("policyholderPartyId") == null) {
            log.warn("{} on policy {} names no policyholder; nobody to tell", templateKey, payload.get("policyNumber"));
            return;
        }
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"),
                    (String) payload.get("policyNumber"), templateKey, values));
        } catch (Exception e) {
            // The money is recorded either way -- its module has committed, and rolling that back to save a message
            // would be exactly backwards. Logged loudly; the dispatch row records the failure for the desk.
            log.error("Failed to send {} on policy {}", templateKey, payload.get("policyNumber"), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    /** The agent's name, read under the event's tenant; blank when it cannot be read -- the message still goes. */
    private String agentName(DomainEventEnvelope<?> envelope, Object agentPartyId) {
        if (agentPartyId == null) return "";
        UUID partyId = agentPartyId instanceof UUID u ? u : UUID.fromString(agentPartyId.toString());
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            return requiresNewTransactionTemplate.execute(status ->
                partyApi.displayNames(java.util.List.of(partyId)).getOrDefault(partyId, ""));
        } catch (RuntimeException e) {
            log.warn("Agent cash on {}: the agent's name could not be read; the message leaves it out",
                envelope.payload(), e);
            return "";
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
