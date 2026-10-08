package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** One payment applied to an invoice: what arrived, when, under which reference, and from whom. */
public record ReceiptView(UUID receiptId, UUID invoiceId, BigDecimal amount, String currency, Instant receivedAt,
                          String paymentReference, String payerRef) {}
