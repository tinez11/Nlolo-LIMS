package tz.co.nlolo.lifeplatform.reporting.api;

import tz.co.nlolo.lifeplatform.billing.api.OpenPremiumView;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One premium to follow up (2026-11-11, analytics plan 1): who to call, how much, how late, and what was already done.
 * {@code daysLate} is 0 for a premium not yet late; {@code lateBand} is null then.
 */
public record FollowUpRow(UUID invoiceId, String policyNumber, String clientName, String clientPhone,
                          String productName, UUID agentId, String agentName, String branchCode, BigDecimal owed,
                          String currency, LocalDate dueDate, long daysLate, String lateBand, String policyStatus,
                          LocalDate lapseExpectedOn, BigDecimal agentCashHeld, String agentCashWith,
                          List<OpenPremiumView.ActionView> actions) {}
