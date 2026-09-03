package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * What a product version will accept: entry age, term and sum assured.
 *
 * <p>Every bound is optional. An unbounded dimension is a real product design, not a
 * gap — a whole-life product has no term to bound, and a product may genuinely accept
 * any sum assured its treaties can carry.
 *
 * <p><b>The bounds are not all the same kind of rule</b>, and Build 4's gates treat them
 * differently. Recorded here rather than as a column because the severity is a property
 * of the kind of bound, not of any one product:
 *
 * <ul>
 *   <li><b>Entry age is hard.</b> {@code base_rate_table} is keyed on
 *       {@code (age_from..age_to, sex, smoker_status)}, and {@code quotePremium} already
 *       throws {@link PremiumNotQuotableException} when no cell matches. An age outside
 *       the bound cannot be priced at all, so refusing it invents nothing — it just says
 *       so while the underwriter can still act on it.</li>
 *   <li><b>Term is hard.</b> Term is staff-entered configuration. A term the product was
 *       not designed for is the wrong product, and the answer is a new version.</li>
 *   <li><b>Sum assured is soft.</b> Above retention is not invalid business — it is what
 *       reinsurance is for, and this platform already models treaties and cessions.
 *       Blocking would refuse business the platform exists to cede. The breach must be
 *       named in {@code reasonForManualIssue} so it lands in the audit trail rather than
 *       being clicked past.</li>
 * </ul>
 *
 * <p>Sum assured carries no currency. The bounds are read in the product's own
 * {@code default_currency}; a second currency here could disagree with it, and a bound
 * in a currency the product does not price in means nothing.
 */
public record EligibilityBounds(
    Integer minEntryAge, Integer maxEntryAge,
    Integer minTermMonths, Integer maxTermMonths,
    BigDecimal minSumAssured, BigDecimal maxSumAssured) {

    public EligibilityBounds {
        requireOrdered("Entry age", minEntryAge, maxEntryAge);
        requireOrdered("Term", minTermMonths, maxTermMonths);
        if (minSumAssured != null && maxSumAssured != null && maxSumAssured.compareTo(minSumAssured) < 0) {
            throw new IllegalArgumentException("Sum assured maximum cannot be below its minimum");
        }
    }

    private static void requireOrdered(String label, Integer min, Integer max) {
        if (min != null && max != null && max < min) {
            throw new IllegalArgumentException(label + " maximum cannot be below its minimum");
        }
    }

    /** No bounds recorded — the version accepts whatever the rate table can price. */
    public static EligibilityBounds none() {
        return new EligibilityBounds(null, null, null, null, null, null);
    }
}
