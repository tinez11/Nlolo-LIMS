package tz.co.nlolo.lifeplatform.billing.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One premium still owed, or about to fall due, with what was done to collect it (2026-11-11, premiums to follow up).
 *
 * @param owed            what is owed now: the premium less what was paid and credited against it
 * @param status          the invoice's own status: DUE, IN_GRACE, OVERDUE or PARTIALLY_PAID
 * @param lapseExpectedOn the day the arrears sweep reaches its lapse level, when an arrears case is open; null otherwise
 * @param agentCashHeld   cash an agent recorded on this policy and the company has not received yet (savings and
 *                        annuity cash waits for banking); null when none
 * @param agentCashAgentId the agent holding the most of it
 * @param actions         what was done, newest first, at most five
 */
public record OpenPremiumView(UUID invoiceId, String policyNumber, LocalDate dueDate, BigDecimal owed, String currency,
                              String status, LocalDate lapseExpectedOn, BigDecimal agentCashHeld, UUID agentCashAgentId,
                              List<ActionView> actions) {

    /** One thing done: a call (with its outcome and any promise), a reminder, or a payment request. */
    public record ActionView(String kind, String outcome, LocalDate promisedOn, String note, String recordedBy,
                             Instant recordedAt) {}
}
