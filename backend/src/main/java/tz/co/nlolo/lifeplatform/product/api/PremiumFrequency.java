package tz.co.nlolo.lifeplatform.product.api;

/**
 * How often a premium is paid, and the divisor that converts an annual premium
 * into one instalment.
 *
 * Values match `openapi-policy.yaml`'s `premiumFrequency` enum exactly
 * (ANNUALLY, not ANNUAL) so a quote's frequency can be carried onto a policy
 * without translation. Policy's own module API types it as a bare String; this is
 * an enum because Product Configuration types its vocabulary
 * (IfrsMeasurementModel, ProductCategory, FactorType, BenefitType are all enums)
 * and because an invalid divisor is not a state worth representing.
 */
public enum PremiumFrequency {
    MONTHLY(12),
    QUARTERLY(4),
    ANNUALLY(1),

    /**
     * Charged once, when cover is written, and never again.
     *
     * <p>{@code instalmentsPerYear} is 0 rather than 1 on purpose. There is no recurring
     * period at all here, and a 1 would read as "annually" to any arithmetic that divides an
     * annual premium by it -- which is precisely the mistake that would put a single-premium
     * contract back onto a billing cycle. Dividing by this value throws, which is the correct
     * outcome: a caller reaching for a per-instalment figure on a contract that has no
     * instalments has already gone wrong.
     *
     * <p>Every caller that schedules by frequency must branch on SINGLE explicitly. See
     * {@code billing.PolicyEventListener.handlePolicyIssued}, which returns without building a
     * schedule, and {@code BillingApiImpl.nextPeriodStart}, which throws by name.
     */
    SINGLE(0);

    private final int instalmentsPerYear;

    PremiumFrequency(int instalmentsPerYear) {
        this.instalmentsPerYear = instalmentsPerYear;
    }

    public int instalmentsPerYear() {
        return instalmentsPerYear;
    }
}
