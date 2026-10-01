package tz.co.nlolo.lifeplatform.product.api;

/**
 * How many instalments one policy year's amount is split into.
 *
 * <p>Deliberately NOT {@link PremiumFrequency}: that one carries SINGLE, which is meaningless for a
 * recurring payout, and its {@code instalmentsPerYear} of 0 throws when divided by. A payout
 * frequency always divides a year into a whole number of equal periods.
 */
public enum PayoutFrequency {
    ANNUAL(1), SEMI_ANNUAL(2), QUARTERLY(4), MONTHLY(12);

    private final int perYear;

    PayoutFrequency(int perYear) { this.perYear = perYear; }

    public int perYear() { return perYear; }

    /** Months between instalments within a policy year: 12, 6, 3 or 1. */
    public int monthsApart() { return 12 / perYear; }
}
