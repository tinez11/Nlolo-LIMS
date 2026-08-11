package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InvoiceView(UUID invoiceId, String policyNumber, LocalDate dueDate,
                           BigDecimal amount, String currency, InvoiceStatus status,
                           LocalDate gracePeriodEndsAt, Integer dunningLevel) {}
