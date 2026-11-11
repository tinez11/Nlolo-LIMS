package tz.co.nlolo.lifeplatform.billing.application;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.billing.api.CollectionActionRefusedException;
import tz.co.nlolo.lifeplatform.billing.api.InvoiceNotFoundException;
import tz.co.nlolo.lifeplatform.billing.api.OpenPremiumView;
import tz.co.nlolo.lifeplatform.billing.domain.CollectionAction;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumCredit;
import tz.co.nlolo.lifeplatform.billing.domain.PremiumInvoice;
import tz.co.nlolo.lifeplatform.billing.infrastructure.CollectionActionRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumCreditRepository;
import tz.co.nlolo.lifeplatform.billing.infrastructure.PremiumInvoiceRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What was done to collect a premium (2026-11-11, premiums to follow up): a call with its outcome, a reminder SMS, a
 * mobile-money payment request. Each is recorded once and never edited. A promise to pay is a note only -- nothing
 * reads it to hold back reminders or dunning (the user, 2026-11-11).
 */
@Service
public class CollectionActions {

    static final Set<String> OUTCOMES = Set.of("WILL_PAY", "CANNOT_PAY_NOW", "NO_ANSWER", "WRONG_NUMBER");
    private static final int NOTE_MAX = 500;
    private static final ZoneId CIVIL = ZoneId.of("Africa/Dar_es_Salaam");

    private final PremiumInvoiceRepository invoices;
    private final PremiumCreditRepository credits;
    private final CollectionActionRepository actions;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher events;

    CollectionActions(PremiumInvoiceRepository invoices, PremiumCreditRepository credits,
                      CollectionActionRepository actions, PolicyApi policyApi, ApplicationEventPublisher events) {
        this.invoices = invoices;
        this.credits = credits;
        this.actions = actions;
        this.policyApi = policyApi;
        this.events = events;
    }

    @Transactional
    public OpenPremiumView.ActionView recordCall(UUID invoiceId, String outcome, LocalDate promisedOn, String note,
                                                 String by) {
        if (outcome == null || outcome.isBlank()) {
            throw new CollectionActionRefusedException("Say what happened on the call");
        }
        if (!OUTCOMES.contains(outcome)) {
            throw new CollectionActionRefusedException("A call ends as one of " + OUTCOMES + ", not " + outcome);
        }
        if (promisedOn != null && !"WILL_PAY".equals(outcome)) {
            throw new CollectionActionRefusedException("A promised date goes only with \"will pay\"");
        }
        if (promisedOn != null && promisedOn.isBefore(LocalDate.now(CIVIL))) {
            throw new CollectionActionRefusedException("The promised date cannot be in the past");
        }
        String trimmed = note == null || note.isBlank() ? null : note.trim();
        if (trimmed != null && trimmed.length() > NOTE_MAX) {
            throw new CollectionActionRefusedException("Keep the note to " + NOTE_MAX + " characters");
        }
        PremiumInvoice invoice = owedInvoice(invoiceId);
        return OpenPremiums.view(actions.save(CollectionAction.call(invoice.getTenantId(), invoiceId,
            invoice.getPolicyNumber(), outcome, promisedOn, trimmed, by)));
    }

    @Transactional
    public OpenPremiumView.ActionView sendReminder(UUID invoiceId, String by) {
        PremiumInvoice invoice = owedInvoice(invoiceId);
        UUID tenantId = invoice.getTenantId();
        if (actions.existsByTenantIdAndInvoiceIdAndKindAndRecordedAtAfter(tenantId, invoiceId,
                CollectionAction.REMINDER, LocalDate.now(CIVIL).atStartOfDay(CIVIL).toInstant())) {
            throw new CollectionActionRefusedException("A reminder was already sent today for this premium");
        }
        BigDecimal owed = invoice.balanceDue(creditedAgainst(invoice));
        CollectionAction saved = actions.save(CollectionAction.reminder(tenantId, invoiceId, invoice.getPolicyNumber(), by));
        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoiceId);
        payload.put("policyNumber", invoice.getPolicyNumber());
        // communication names the client from this; it may not read policy (the same reason PremiumCollected carries it).
        payload.put("policyholderPartyId", policyApi.getPolicy(invoice.getPolicyNumber()).policyholderPartyId());
        payload.put("amount", Map.of("amount", owed.toPlainString(), "currencyCode", invoice.getCurrency().trim()));
        payload.put("dueDate", invoice.getDueDate().toString());
        events.publishEvent(DomainEventEnvelope.of("billing.PremiumReminderSent", tenantId, payload));
        return OpenPremiums.view(saved);
    }

    /** A mobile-money request was sent for this premium (BillingApiImpl.requestPaymentForInvoice). */
    @Transactional
    public void paymentRequested(PremiumInvoice invoice, String by) {
        actions.save(CollectionAction.paymentRequest(invoice.getTenantId(), invoice.getInvoiceId(),
            invoice.getPolicyNumber(), by));
    }

    private PremiumInvoice owedInvoice(UUID invoiceId) {
        PremiumInvoice invoice = invoices.findByInvoiceIdAndTenantId(invoiceId, TenantContext.get())
            .orElseThrow(() -> new InvoiceNotFoundException(invoiceId));
        if (!OpenPremiums.OPEN.contains(invoice.getStatus())
                || invoice.balanceDue(creditedAgainst(invoice)).signum() <= 0) {
            throw new CollectionActionRefusedException("This premium is no longer owed");
        }
        return invoice;
    }

    private BigDecimal creditedAgainst(PremiumInvoice invoice) {
        return credits.findByTenantIdAndOriginalInvoiceId(invoice.getTenantId(), invoice.getInvoiceId()).stream()
            .map(PremiumCredit::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
