package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One premium received on a policy (2026-10-08, the customer portal design step 3), for the document centre's list of
 * receipts.
 *
 * @param forPremiumDue the due date of the premium it paid; null if that premium could not be found
 */
public record ReceiptLine(UUID receiptId, LocalDate receivedOn, BigDecimal amount, String currency, String reference,
                          String paidBy, LocalDate forPremiumDue) {}
