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
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Tells a savings customer, once a year, what their account holds (product step 3, spec §6).
 *
 * <p>Fires only on the ANNUAL statement ({@code annual: true}). An on-demand statement is a member
 * of staff's, and staff deliver it -- the customer portal is deferred and email is Mailpit-only, so
 * there is no automatic delivery route for the PDF itself; this one line is what does reach them.
 *
 * <p>Both figures come off the statement accumulation filed, which reconciled against the ledger
 * before it was filed -- so the SMS never states a number the customer could not find again on the
 * PDF. The policyholder is named by the event, because this module may not depend on accumulation
 * or policy to look them up.
 */
@Component("communicationAccumulationEventListener")
public class AccumulationEventListener {

    private static final Logger log = LoggerFactory.getLogger(AccumulationEventListener.class);

    private final NotificationApi notificationApi;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public AccumulationEventListener(NotificationApi notificationApi, PlatformTransactionManager transactionManager) {
        this.notificationApi = notificationApi;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"accumulation.StatementIssued".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) envelope.payload();
        if (!Boolean.TRUE.equals(payload.get("annual"))) {
            return;
        }

        UUID previousTenant = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            String policyNumber = (String) payload.get("policyNumber");
            requiresNewTransactionTemplate.executeWithoutResult(status ->
                notificationApi.notify(envelope.eventId(), (UUID) payload.get("policyholderPartyId"),
                    policyNumber, "ACCOUNT_STATEMENT",
                    Map.of(
                        "policyNumber", policyNumber,
                        "year", String.valueOf(LocalDate.parse((String) payload.get("periodTo")).getYear()),
                        "balance", money(payload.get("closingBalance")),
                        "interest", money(payload.get("interestCredited")))));
        } catch (Exception e) {
            // The statement is filed either way; rolling that back to save an SMS would be backwards.
            // Logged loudly; the dispatch row records the failure for the desk.
            log.error("Failed to send the annual statement summary for policy {}", payload.get("policyNumber"), e);
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
