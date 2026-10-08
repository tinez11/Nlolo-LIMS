package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One premium received on a policy (2026-10-08, the customer portal design step 3), for the document centre's list of
 * receipts.
 *
 * @param coversFrom the first day of cover the premium it paid buys; with {@code coversTo}, what a customer reads as
 *     "this paid for". Billing dates an instalment at the END of its period (premium in arrears), so the invoice's own
 *     due date -- a year on, for an annual premium paid on day one -- misreads as when it was owed. Null where the
 *     invoice could not be found or the premium is single.
 */
public record ReceiptLine(UUID receiptId, LocalDate receivedOn, BigDecimal amount, String currency, String reference,
                          String paidBy, LocalDate coversFrom, LocalDate coversTo) {}
