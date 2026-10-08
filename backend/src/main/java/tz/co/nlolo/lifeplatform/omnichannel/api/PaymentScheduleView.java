package tz.co.nlolo.lifeplatform.omnichannel.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A policy's payment schedule (2026-10-07): every premium due, what was paid against it, when, under which
 * receipt and by whom, and what is outstanding. The console's table and the PDF and Excel downloads are built
 * from this one view.
 */
public record PaymentScheduleView(String policyNumber, String policyholderName, String productName, String policyStatus,
                                  BigDecimal premium, String premiumFrequency, String currency, LocalDate coverStart,
                                  List<Line> lines, Totals totals) {

    /**
     * One premium due.
     *
     * @param status   Paid, Partly paid, Due, In grace, Overdue or Waived
     * @param paidOn   the day the last payment against it arrived; null when nothing has
     * @param receipts the receipt references, comma-separated; null when none
     * @param paidBy   who paid (phone, "cash"), comma-separated; null when nothing has
     */
    public record Line(int number, UUID invoiceId, LocalDate dueDate, BigDecimal amountDue, BigDecimal amountPaid,
                       LocalDate paidOn, String receipts, String paidBy, String status, BigDecimal balance,
                       /* The cover the premium pays for (billing V11); null where not recorded. */
                       LocalDate coversFrom, LocalDate coversTo) {}

    /**
     * @param charged     every premium due on the schedule, waived ones left out
     * @param paid        every payment received
     * @param outstanding unpaid on premiums already due (due date today or earlier)
     * @param upcoming    unpaid on premiums not yet due
     * @param nextDueDate the earliest premium with a balance; null when none
     */
    public record Totals(BigDecimal charged, BigDecimal paid, BigDecimal outstanding, BigDecimal upcoming,
                         LocalDate nextDueDate, BigDecimal nextDueAmount) {}
}
