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
 * <p>Since receipts step 1 (2026-11-05) every amount received is one of these -- a mobile-money payment as much as a
 * cheque recorded by hand -- and carries its receipt number once confirmed.
 *
 * @param status AWAITING_APPROVAL until decided; CONFIRMED once it pays the invoice or the loan; REJECTED if refused
 * @param receiptNumber RC-year-sequence, given when it was confirmed; null before, and on one never confirmed
 */
public record ReceiptView(UUID receiptId, String purpose, String method, String reference, String payerRef,
                          BigDecimal amount, String currency, UUID invoiceId, UUID loanId, String policyNumber,
                          LocalDate receivedOn, String note, UUID fieldReceiptId, String status, String recordedBy,
                          Instant recordedAt, String decidedBy, Instant decidedAt, String decisionReason,
                          String receiptNumber) {}
