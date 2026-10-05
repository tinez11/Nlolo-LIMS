package tz.co.nlolo.lifeplatform.communication.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.communication.api.NotificationApi;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Family funeral cover's notices to the main member: a life added, a life's cover ended, the premium
 * changed. AnnuityEventListener's shape exactly. A death sends nothing: the family is already in a claim,
 * and a "cover ended" message about someone who has just died is cruel.
 */
@Component
public class FuneralEventListener {

    private static final Logger log = LoggerFactory.getLogger(FuneralEventListener.class);
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NotificationApi notificationApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public FuneralEventListener(NotificationApi notificationApi, PlatformTransactionManager transactionManager) {
        this.notificationApi = notificationApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String type = envelope.eventType();
        if (!"policy.CoveredLifeAdded".equals(type) && !"policy.CoveredLifeEnded".equals(type)
                && !"policy.PremiumRestated".equals(type)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        if ("policy.CoveredLifeEnded".equals(type) && "DECEASED".equals(payload.get("endReason"))) {
            return;
        }
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            String policyNumber = (String) payload.get("policyNumber");
            UUID recipient = (UUID) payload.get("policyholderPartyId");
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                switch (type) {
                    case "policy.CoveredLifeAdded" -> notificationApi.notify(envelope.eventId(), recipient, policyNumber,
                        "FUNERAL_LIFE_ADDED", Map.of("policyNumber", policyNumber, "fullName", (String) payload.get("fullName"),
                            "coverStart", day(payload.get("coverStart"))));
                    case "policy.CoveredLifeEnded" -> notificationApi.notify(envelope.eventId(), recipient, policyNumber,
                        "FUNERAL_LIFE_ENDED", Map.of("policyNumber", policyNumber, "fullName", (String) payload.get("fullName"),
                            "endedOn", day(payload.get("endedOn"))));
                    default -> {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> premium = (Map<String, Object>) payload.get("premiumAmount");
                        notificationApi.notify(envelope.eventId(), recipient, policyNumber, "FUNERAL_PREMIUM_CHANGED",
                            Map.of("policyNumber", policyNumber, "amount", (String) premium.get("amount"),
                                "currency", (String) premium.get("currencyCode"), "effectiveFrom", day(payload.get("effectiveFrom"))));
                    }
                }
            });
        } catch (Exception e) {
            // The dispatch row records any failure for the desk; the policy change itself stands.
            log.error("Failed to send the {} notice for policy {}", type, payload.get("policyNumber"), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    private static String day(Object isoDate) {
        return LocalDate.parse((String) isoDate).format(DAY_MONTH_YEAR);
    }
}
