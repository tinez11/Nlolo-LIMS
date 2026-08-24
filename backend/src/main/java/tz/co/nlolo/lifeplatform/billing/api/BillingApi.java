package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BillingApi {

    InvoiceView getNextDueInvoice(String policyNumber);
    List<InvoiceView> listInvoices(String policyNumber, InvoiceStatus status);

    /**
     * Single-invoice read by id, needed for object-level authorization on endpoints that identify
     * an invoice without naming its policy (BillingController.requestPaymentForInvoice). Tenant
     * scoping is applied inside the implementation, never taken from the caller.
     */
    InvoiceView getInvoice(UUID invoiceId);

    InvoiceView waiveInvoice(UUID invoiceId, String reason, String waivedBy);

    /**
     * Requests collection of an invoice's premium through payment (M5). Publishes
     * billing.PaymentRequested, whose declared payload shape is api/asyncapi-events.yaml.
     *
     * <p><b>Review fix (I1): the idempotency key is now a CALLER-SUPPLIED value, not
     * {@code invoiceId.toString()}.</b> The invoice id looked like the perfect key -- stable across
     * redelivery, unique per invoice -- and it is exactly wrong: {@code payment} drops any event
     * whose {@code (tenant_id, idempotency_key)} pair is already claimed, so the FIRST attempt
     * permanently consumed the invoice's ONLY key. After an ordinary {@code INSUFFICIENT_FUNDS}
     * decline, an operator retry reached the rail ZERO times (empirically proven) while still
     * returning HTTP 202 with nothing but an INFO log, and the invoice became uncollectable through
     * this platform forever. A {@code PARTIALLY_PAID} top-up -- a state this milestone newly made
     * reachable -- was equally unresolvable.
     *
     * <p>Semantics, which the caller is responsible for honouring and the HTTP layer enforces via a
     * required {@code Idempotency-Key} header: the same key means the same INTENT and is deduped by
     * {@code payment} (so a double-click or a redelivered request is still safe), while a NEW key
     * means a NEW attempt and is allowed to reach the rail. This is the platform's existing
     * {@code Idempotency-Key} convention (openapi-common.yaml's shared parameter), promoted from
     * "accepted but not enforced" to genuinely load-bearing for this one endpoint.
     *
     * @param idempotencyKey required, non-blank. Deliberately has NO default: any default
     *        (the invoice id, a timestamp, a random UUID) reintroduces either the original
     *        single-shot bug or the loss of duplicate-submission protection. A missing or blank key
     *        is rejected rather than substituted.
     */
    void requestPaymentForInvoice(UUID invoiceId, String payerRef, String idempotencyKey);

    /** Applies a payment.PaymentConfirmed collection to the invoice it was requested for (M5) --
     * see PremiumInvoice.applyPayment for the PAID/PARTIALLY_PAID decision and WAIVED's
     * no-overwrite rule. Also resolves any open ArrearsCase for the invoice, reusing the exact
     * ArrearsCase::resolve wiring waiveInvoice already uses. currency/paymentReference are
     * accepted for parity with the PaymentConfirmed payload and for future reconciliation use;
     * premium_invoice has no column for either today, so neither is persisted or compared here. */
    InvoiceView applyConfirmedPayment(UUID invoiceId, BigDecimal amount, String currency, String paymentReference);

    record FieldReceiptResult(UUID receiptId, String status) {}
    FieldReceiptResult captureFieldReceipt(UUID agentId, String policyNumber, BigDecimal amount, String currency,
                                            String clientIdempotencyKey, Instant capturedAtClient);
}
