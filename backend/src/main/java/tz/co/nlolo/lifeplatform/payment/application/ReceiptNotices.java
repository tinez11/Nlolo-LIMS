package tz.co.nlolo.lifeplatform.payment.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.payment.domain.PaymentTransaction;
import tz.co.nlolo.lifeplatform.payment.infrastructure.PaymentTransactionRepository;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * payment.ReceiptIssued: an amount received was confirmed and numbered (receipts step 1, 2026-11-05), so the customer
 * is told -- once per amount received, quoting the receipt number. Every road to CONFIRMED calls it after the flush
 * that numbers the row: a mobile-money callback, a second person's approval, a top-up recorded as received.
 *
 * <p>Names the policyholder, because communication can only write to a party and cannot read a policy. Money with no
 * policy named has no one to tell, and is skipped.
 */
@Component
class ReceiptNotices {

    private static final Logger log = LoggerFactory.getLogger(ReceiptNotices.class);

    private final PaymentTransactionRepository transactions;
    private final PolicyApi policyApi;
    private final ApplicationEventPublisher events;

    ReceiptNotices(PaymentTransactionRepository transactions, PolicyApi policyApi, ApplicationEventPublisher events) {
        this.transactions = transactions;
        this.policyApi = policyApi;
        this.events = events;
    }

    /** Called after the flush that turned it CONFIRMED, in the caller's tenant and transaction. */
    void issued(PaymentTransaction t) {
        String number = transactions.receiptNumberOf(t.getPaymentTransactionId());
        if (number == null || t.getPolicyNumber() == null) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("receiptId", t.getPaymentTransactionId());
        payload.put("receiptNumber", number);
        payload.put("policyNumber", t.getPolicyNumber());
        payload.put("amount", Map.of("amount", t.getAmount().toPlainString(), "currencyCode", t.getCurrency()));
        payload.put("purpose", t.getPurpose());
        payload.put("method", t.getMethod());
        UUID holder = policyholderOf(t.getPolicyNumber());
        if (holder != null) {
            payload.put("policyholderPartyId", holder);
        }
        events.publishEvent(DomainEventEnvelope.of("payment.ReceiptIssued", t.getTenantId(), payload));
    }

    /** Null, and logged, if the policy cannot be read: the money is confirmed either way. */
    private UUID policyholderOf(String policyNumber) {
        try {
            return policyApi.getPolicy(policyNumber).policyholderPartyId();
        } catch (RuntimeException e) {
            log.warn("Receipt for policy {} names no policyholder: the policy could not be read", policyNumber, e);
            return null;
        }
    }
}
