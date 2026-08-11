package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface BillingApi {

    InvoiceView getNextDueInvoice(String policyNumber);
    List<InvoiceView> listInvoices(String policyNumber, InvoiceStatus status);
    InvoiceView waiveInvoice(UUID invoiceId, String reason, String waivedBy);

    record FieldReceiptResult(UUID receiptId, String status) {}
    FieldReceiptResult captureFieldReceipt(UUID agentId, String policyNumber, BigDecimal amount, String currency,
                                            String clientIdempotencyKey, Instant capturedAtClient);
}
