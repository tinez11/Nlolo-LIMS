package tz.co.nlolo.lifeplatform.product.api;

import java.util.Map;

/**
 * The calendar of an annuity frequency, in one place, so the pricer, the lock and the payout engine
 * never disagree about how many payments a year is or how far apart they fall.
 */
public final class AnnuityFrequencies {

    private static final Map<String, Integer> MONTHS = Map.of("MONTHLY", 1, "QUARTERLY", 3, "SEMI_ANNUAL", 6, "ANNUAL", 12);

    private AnnuityFrequencies() {}

    public static boolean isKnown(String frequency) {
        return frequency != null && MONTHS.containsKey(frequency);
    }

    /** Months between two payments. */
    public static int monthsPer(String frequency) {
        Integer months = frequency != null ? MONTHS.get(frequency) : null;
        if (months == null) {
            throw new IllegalArgumentException("Not an annuity payment frequency: " + frequency);
        }
        return months;
    }

    public static int paymentsPerYear(String frequency) {
        return 12 / monthsPer(frequency);
    }
}
