package tz.co.nlolo.lifeplatform.policy.api;

import java.math.BigDecimal;

/**
 * What a policy may be lent against (2026-11-07, the user: the Loans page shows how the limit is reached).
 *
 * @param value                 the policy's value: its cash value, or a savings account's balance
 * @param maxLoanToValuePercent the most a loan may be, as a share of the value, from the policy's own product version;
 *                              0 lends nothing, null the whole value (every version published before the limit existed)
 * @param limit                 value × that share: the most all of the policy's loans may owe together
 * @param borrowed              what is borrowed and not yet repaid (the principal pledged against the value)
 * @param reserved              loans being taken right now, not yet confirmed
 * @param available             limit − borrowed − reserved, never below zero; the policyloan module also takes the
 *                              interest owed off it, which only it knows
 */
public record LoanValueView(String policyNumber, String currency, BigDecimal value, BigDecimal maxLoanToValuePercent,
                            BigDecimal limit, BigDecimal borrowed, BigDecimal reserved, BigDecimal available) {

    /** The product lends nothing on this version. */
    public boolean lends() {
        return maxLoanToValuePercent == null || maxLoanToValuePercent.signum() > 0;
    }
}
