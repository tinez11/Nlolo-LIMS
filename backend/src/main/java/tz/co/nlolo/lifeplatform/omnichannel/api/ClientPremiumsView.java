package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A client's premiums across all their policies (2026-11-12, the client Premiums tab): every open invoice up to the
 * window, overdue first; the three figures summed from those rows in {@code currency} (TZS -- another currency is
 * listed, never added in); the newest receipt across all their policies; and the policies with nothing raised ahead.
 */
public record ClientPremiumsView(
    String window,
    long overdueCount, BigDecimal overdueOwed,
    LocalDate nextDueOn, BigDecimal nextDueAmount,
    long dueInWindowCount, BigDecimal dueInWindowOwed,
    String currency,
    LastReceipt lastReceipt,
    List<Row> rows,
    List<NotInvoiced> notInvoicedAhead) {

    public record LastReceipt(UUID receiptId, String receiptNumber, String policyNumber, LocalDate receivedOn,
                              BigDecimal amount, String currency) {}

    /** @param status OVERDUE, IN_GRACE, DUE or PARTIALLY_PAID; {@code owed} is what is still owed on it */
    public record Row(UUID invoiceId, String policyNumber, String productName, LocalDate dueDate, BigDecimal owed,
                      String currency, String status) {}

    /** @param how ONCE (a single premium, already raised) or PER_ENROLMENT_FILE (a lender's scheme) */
    public record NotInvoiced(String policyNumber, String productName, String how) {}
}
