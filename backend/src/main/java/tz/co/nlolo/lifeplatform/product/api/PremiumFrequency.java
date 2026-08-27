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
    ANNUALLY(1);

    private final int instalmentsPerYear;

    PremiumFrequency(int instalmentsPerYear) {
        this.instalmentsPerYear = instalmentsPerYear;
    }

    public int instalmentsPerYear() {
        return instalmentsPerYear;
    }
}
