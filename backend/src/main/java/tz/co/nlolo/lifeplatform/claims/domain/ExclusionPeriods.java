package tz.co.nlolo.lifeplatform.claims.domain;

/**
 * How long a product's exclusion windows run, in months from cover start.
 *
 * <p>Null means the product has no such exclusion, which is every product on this platform
 * before credit life. Absent is not zero: zero would be a window that closes immediately and
 * still exists, which is a different and meaningless thing to configure.
 *
 * @param suicideMonths the client confirmed 12 for credit life on 2026-09-22
 * @param preExistingMonths likewise 12, and independent — nothing may assume they match
 */
public record ExclusionPeriods(Integer suicideMonths, Integer preExistingMonths) {

    public ExclusionPeriods {
        requireSaneWindow("suicideMonths", suicideMonths);
        requireSaneWindow("preExistingMonths", preExistingMonths);
    }

    /** Every product that is not credit life, today. */
    public static ExclusionPeriods none() {
        return new ExclusionPeriods(null, null);
    }

    private static void requireSaneWindow(String field, Integer months) {
        if (months != null && months <= 0) {
            throw new IllegalArgumentException(field + " of " + months + " is not a window;"
                + " leave it absent for a product with no such exclusion");
        }
    }
}
