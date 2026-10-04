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
 * Tells a pension holder their pension is about to start (product step 5 D2): 90 and 30 days before
 * it vests, so they can choose how it is paid -- with no instruction it vests into the version's
 * default with no lump sum. The policyholder is named by the event; this module may not depend on
 * annuity or policy to look them up.
 */
@Component("communicationAnnuityEventListener")
public class AnnuityEventListener {

    private static final Logger log = LoggerFactory.getLogger(AnnuityEventListener.class);
    private static final DateTimeFormatter DAY_MONTH_YEAR = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final NotificationApi notificationApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public AnnuityEventListener(NotificationApi notificationApi, PlatformTransactionManager transactionManager) {
        this.notificationApi = notificationApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"annuity.VestingReminderDue".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            String policyNumber = (String) payload.get("policyNumber");
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"),
                    policyNumber, "PENSION_VESTING_REMINDER",
                    Map.of("policyNumber", policyNumber,
                           "vestingDate", LocalDate.parse((String) payload.get("vestingDate")).format(DAY_MONTH_YEAR))));
        } catch (Exception e) {
            // The reminder is recorded as sent either way; the dispatch row records the failure for the desk.
            log.error("Failed to send the vesting reminder for policy {}", payload.get("policyNumber"), e);
        } finally {
            if (previousTenant != null) {
                TenantContext.set(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }
}
