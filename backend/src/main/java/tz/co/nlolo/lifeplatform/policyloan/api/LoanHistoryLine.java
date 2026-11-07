package tz.co.nlolo.lifeplatform.policyloan.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line of a loan's history (2026-11-07): paid out, interest added, repaid, repaid out of a payout, or reversed.
 *
 * @param kind      DISBURSEMENT, INTEREST_ACCRUAL, REPAYMENT, SETTLEMENT (repaid out of a payout) or REVERSAL
 * @param reference the payment or payout it came from -- for a SETTLEMENT, "CLAIM:&lt;id&gt;", "MATURITY:&lt;id&gt;"...
 */
public record LoanHistoryLine(String kind, BigDecimal amount, String currency, Instant at, String reference) {}
