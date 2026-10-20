package tz.co.nlolo.lifeplatform.benefitpayout.api;

import tz.co.nlolo.lifeplatform.product.api.PayoutKind;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One amount a policy on these terms would be owed, as issuance would schedule it (2026-10-20, the prospect quotation).
 *
 * @param amount null for a premium return or an account's value, which depend on what is collected and earned
 */
public record PlannedPayoutView(PayoutKind kind, LocalDate dueDate, BigDecimal amount) {}
