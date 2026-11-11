package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One premium that fell due in a period, paid or not (2026-11-11, the collection rate): what was charged and what is
 * still owed on it. Paid is {@code amount - owed}.
 */
public record InvoiceDueView(UUID invoiceId, String policyNumber, LocalDate dueDate, BigDecimal amount, BigDecimal owed,
                             String currency, String status) {}
