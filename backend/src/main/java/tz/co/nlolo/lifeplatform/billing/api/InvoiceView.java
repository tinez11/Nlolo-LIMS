package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * @param amount what was CHARGED. It never changes after the invoice is raised -- credits are
 *     their own rows -- so it is not what is owed; {@code balanceDue} is.
 * @param amountPaid collected against it so far
 * @param amountCredited given back off it: premium refunded to members who left early
 * @param balanceDue charged, less paid, less credited, never negative -- what the policyholder
 *     actually owes, and what a payment request asks for
 * @param enrolmentSubmissionId the monthly file that raised this invoice, on credit life; null
 *     for a scheduled premium
 */
public record InvoiceView(UUID invoiceId, String policyNumber, LocalDate dueDate,
                           BigDecimal amount, String currency, InvoiceStatus status,
                           LocalDate gracePeriodEndsAt, Integer dunningLevel,
                           BigDecimal amountPaid, BigDecimal amountCredited, BigDecimal balanceDue,
                           UUID enrolmentSubmissionId) {}
