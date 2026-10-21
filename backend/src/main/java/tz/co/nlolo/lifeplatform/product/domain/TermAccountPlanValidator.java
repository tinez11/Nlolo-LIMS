package tz.co.nlolo.lifeplatform.product.domain;

import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;

/**
 * Everything a CHECK cannot say about a term account (2026-10-21): an ACCOUNT version whose rate
 * each policy locks at sale from a band, and which pays nothing out until it matures. Pure and
 * static, like {@link DepositPlanValidator}, and run after {@link AccumulationPlanValidator} --
 * a term account is an account first, so its categories, charges and floor are that validator's.
 *
 * <p>Not checked here because a sibling already refuses it: a deposit grid alongside (the deposit
 * validator refuses any account block on a deposit) and a cash-value scale (the account one).
 */
public final class TermAccountPlanValidator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private TermAccountPlanValidator() {}

    public static void validate(AccumulationPlan accumulation, PayoutPlan payout, EligibilityBounds bounds) {
        if (accumulation == null || !accumulation.isAccount()) {
            return;
        }
        BigDecimal min = accumulation.minRatePercent();
        BigDecimal max = accumulation.maxRatePercent();
        if (min == null && max == null) {
            return; // an ordinary savings account
        }
        if (min == null || max == null) {
            fail("A term account needs both a minimum and a maximum rate");
        }
        if (outOfRange(min) || outOfRange(max)) {
            fail("A term account's rates must be between 0 and 100 percent");
        }
        if (max.compareTo(min) < 0) {
            fail("A term account's maximum rate cannot be below its minimum");
        }
        if (bounds == null || bounds.minTermMonths() == null || bounds.maxTermMonths() == null) {
            fail("A term account needs a minimum and a maximum term: its maturity date comes from the term each case names");
        }
        if (payout != null && !payout.rows().isEmpty()) {
            fail("A term account matures through its account; it carries no payout schedule");
        }
    }

    private static boolean outOfRange(BigDecimal rate) { return rate.signum() < 0 || rate.compareTo(HUNDRED) > 0; }

    private static void fail(String message) { throw new InvalidProductVersionException(message); }
}
