package tz.co.nlolo.lifeplatform.communication.application;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;
import tz.co.nlolo.lifeplatform.refdata.api.ReferenceDataApi;
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
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Tells a customer where their offer stands.
 *
 * <p>Three events, three messages: the offer exists and has a deadline, the money arrived and
 * cover started, the offer closed unpaid. Together they are the reason offer-and-acceptance is
 * fair rather than a trap — a thirty-day deadline nobody is told about is not a deadline.
 *
 * <p>{@code AFTER_COMMIT} with {@code PROPAGATION_REQUIRES_NEW}, for the reason
 * {@code policy.application.UnderwritingDecisionEventListener} documents at length: at
 * AFTER_COMMIT the producer's transaction has physically committed but Spring has not unbound its
 * resources, so a plain REQUIRED call silently joins an already-committed transaction and the
 * writes never land. Caught empirically on this codebase.
 *
 * <p>Every handler is wrapped in a catch-all that logs. A failed notification must never roll
 * back a policy or a payment: the customer is insured either way, and losing the cover to save
 * the message would be exactly backwards.
 *
 * <p><b>Bean name is explicit.</b> {@code billing}, {@code distribution}, {@code reinsurance} and
 * {@code regreporting} each already declare a {@code PolicyEventListener}; a fifth unqualified
 * one is a bean-name collision that fails context startup for the whole suite.
 */
@Component("communicationPolicyEventListener")
public class PolicyEventListener {

    private static final Logger log = LoggerFactory.getLogger(PolicyEventListener.class);

    private final NotificationApi notificationApi;
    private final ReferenceDataApi referenceDataApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public PolicyEventListener(NotificationApi notificationApi, ReferenceDataApi referenceDataApi,
                                PlatformTransactionManager transactionManager) {
        this.notificationApi = notificationApi;
        this.referenceDataApi = referenceDataApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        switch (envelope.eventType()) {
            case "policy.PolicyIssued" -> withTenant(envelope, payload -> offerMade(envelope, payload));
            case "policy.PolicyActivated" -> withTenant(envelope, payload -> coverStarted(envelope, payload));
            case "policy.PolicyNotTakenUp" -> withTenant(envelope, payload -> offerExpired(envelope, payload));
            default -> { /* not customer-facing, or not yet */ }
        }
    }

    /**
     * The offer exists, nobody is covered, and there is a deadline.
     *
     * <p>Only for a policy that is actually an offer. A manual issuance whose basis already
     * carries cover (MIGRATION, CONVERSION, REINSTATEMENT) publishes PolicyIssued and
     * PolicyActivated together, and telling that customer to "pay by DATE to start your cover"
     * would be plainly false — they are already insured. The status comes off the payload rather
     * than a lookup because communication may not depend on policy.
     */
    private void offerMade(DomainEventEnvelope<?> envelope, Map<String, Object> payload) {
        if (!"PROPOSED".equals(payload.get("status"))) {
            return;
        }
        LocalDate issueDate = LocalDate.parse((String) payload.get("issueDate"));
        notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"),
            (String) payload.get("policyNumber"), "OFFER_MADE",
            Map.of(
                "policyNumber", (String) payload.get("policyNumber"),
                "premium", money(payload.get("premium")),
                "expiryDate", issueDate.plusDays(offerValidityDays()).toString()));
    }

    /** The money arrived. The one message that says somebody is now insured. */
    private void coverStarted(DomainEventEnvelope<?> envelope, Map<String, Object> payload) {
        notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"),
            (String) payload.get("policyNumber"), "COVER_STARTED",
            Map.of("policyNumber", (String) payload.get("policyNumber")));
    }

    /** The offer closed unpaid, and the customer is not covered. */
    private void offerExpired(DomainEventEnvelope<?> envelope, Map<String, Object> payload) {
        notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"),
            (String) payload.get("policyNumber"), "OFFER_EXPIRED",
            Map.of("policyNumber", (String) payload.get("policyNumber")));
    }

    /**
     * How long the customer has, read at send time rather than baked into the message.
     *
     * <p>Falls back to the seeded 30 rather than throwing. A refdata lookup failing must not cost
     * the customer the whole notification — a message with a slightly wrong date still tells
     * somebody they need to pay, and no message at all tells them nothing.
     */
    private long offerValidityDays() {
        try {
            return Long.parseLong(referenceDataApi.getValue("TZ_OFFER_VALIDITY_DAYS", "TZ"));
        } catch (RuntimeException e) {
            log.warn("TZ_OFFER_VALIDITY_DAYS unreadable; using 30 for the offer deadline", e);
            return 30L;
        }
    }

    /** "TZS 50,000.00" -- grouped, because a customer reads this on a phone. */
    private static String money(Object moneyPayload) {
        @SuppressWarnings("unchecked")
        Map<String, Object> money = (Map<String, Object>) moneyPayload;
        // Locale.ROOT and an explicit pattern, not the JVM default locale: the message a customer
        // receives must not depend on which machine happened to send it.
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
        return money.get("currencyCode") + " " + format.format(new BigDecimal((String) money.get("amount")));
    }

    private void withTenant(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        // Save and restore rather than clear: an AFTER_COMMIT listener runs synchronously on the
        // SAME thread as whatever committed the producer's transaction, so an unconditional
        // clear() would wipe a caller's own still-in-use context.
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNewTransactionTemplate.executeWithoutResult(status -> handler.accept(payload));
        } catch (Exception e) {
            log.error("communication failed to process {} for tenant {}",
                envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
