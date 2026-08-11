package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BillingApi {

    InvoiceView getNextDueInvoice(String policyNumber);
    List<InvoiceView> listInvoices(String policyNumber, InvoiceStatus status);
    InvoiceView waiveInvoice(UUID invoiceId, String reason, String waivedBy);

    /** Requests collection of an invoice's premium through payment (M5). Publishes
     * billing.PaymentRequested, whose declared payload shape is
     * api/asyncapi-events.yaml:493-497. The idempotency key is the invoice id: stable across
     * redelivery and unique per invoice, which is exactly the dedup semantics payment needs. */
    void requestPaymentForInvoice(UUID invoiceId, String payerRef);

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
