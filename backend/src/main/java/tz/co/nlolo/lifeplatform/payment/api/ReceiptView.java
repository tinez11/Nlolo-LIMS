package tz.co.nlolo.lifeplatform.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A recorded receipt (2026-10-27): money a person says came in, by a method the gateway does not pull, and whether a
 * second person has confirmed it. It pays an invoice ({@code purpose} PREMIUM) or, since money movement step 1
 * (2026-11-04), repays a loan ({@code purpose} LOAN_REPAYMENT): exactly one of {@code invoiceId} and {@code loanId}.
 *
 * @param status AWAITING_APPROVAL until decided; CONFIRMED once it pays the invoice or the loan; REJECTED if refused
 */
public record ReceiptView(UUID receiptId, String purpose, String method, String reference, String payerRef,
                          BigDecimal amount, String currency, UUID invoiceId, UUID loanId, String policyNumber,
                          LocalDate receivedOn, String note, UUID fieldReceiptId, String status, String recordedBy,
                          Instant recordedAt, String decidedBy, Instant decidedAt, String decisionReason) {}
