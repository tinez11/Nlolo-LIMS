package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One row of the collections queue: a policy in arrears, how far it has escalated, and what is
 * owed.
 *
 * <p>The amount and due date come from the invoice the case was opened against, not from the
 * case itself. An arrears case carries only an {@code invoiceId}, and a queue that showed a
 * policy number and a dunning level without the money would not be a queue anyone could work --
 * the first question a collections officer asks is how much.
 *
 * <p>{@code lastNotifiedDunningLevel} is deliberately exposed. It is the difference between "we
 * have chased this customer at this level" and "this level was reached and nobody has been
 * told", which for a long time was the same thing on this platform because the notification
 * sweep had no caller. Showing it means a gap between it and {@code dunningLevel} is visible on
 * the screen rather than only in the database.
 */
public record ArrearsCaseView(
    UUID arrearsCaseId,
    String policyNumber,
    UUID invoiceId,
    int dunningLevel,
    int lastNotifiedDunningLevel,
    Instant openedAt,
    Instant resolvedAt,
    /** Null only if the invoice behind the case has since been deleted, which nothing does. */
    BigDecimal amount,
    String currency,
    LocalDate dueDate,
    InvoiceStatus invoiceStatus) {}
