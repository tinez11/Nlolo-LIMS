package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * One benefit a product version covers, and what it pays.
 *
 * <p><b>The number a claim is valued at.</b> Until this existed there was none: {@code
 * claimableCover} took no claim type and returned the policy's full sum assured for every claim,
 * so a critical-illness claim paid a death benefit — a survivable condition valued at the whole
 * cover, on a rider nobody had costed.
 *
 * <p>The compact constructor enforces the shape so an impossible benefit cannot be constructed
 * anywhere, the same arrangement as {@link EligibilityBounds}, {@link FrequencyLoading} and
 * {@link TiraFiling}. {@code benefit_schedule_amount_shape} enforces the same at the database.
 */
public record BenefitDefinition(BenefitType benefitType, BenefitCalculationMethod calculationMethod,
                                 BigDecimal percent, BigDecimal flatAmount) {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public BenefitDefinition {
        if (benefitType == null || calculationMethod == null) {
            throw new IllegalArgumentException("A benefit needs a type and a calculation method");
        }
        switch (calculationMethod) {
            case SUM_ASSURED -> {
                if (percent != null || flatAmount != null) {
                    throw new IllegalArgumentException("A SUM_ASSURED benefit pays the whole cover,"
                        + " so it carries neither a percentage nor a flat amount");
                }
            }
            case PERCENTAGE_OF_SUM_ASSURED -> {
                if (percent == null) {
                    throw new IllegalArgumentException(
                        "A PERCENTAGE_OF_SUM_ASSURED benefit needs a percentage");
                }
                if (flatAmount != null) {
                    throw new IllegalArgumentException(
                        "A PERCENTAGE_OF_SUM_ASSURED benefit carries no flat amount");
                }
                if (percent.signum() <= 0 || percent.compareTo(HUNDRED) > 0) {
                    throw new IllegalArgumentException("A benefit percentage must be greater than"
                        + " zero and no more than 100, was " + percent.toPlainString());
                }
            }
            case FLAT_AMOUNT -> {
                if (flatAmount == null) {
                    throw new IllegalArgumentException("A FLAT_AMOUNT benefit needs a flat amount");
                }
                if (percent != null) {
                    throw new IllegalArgumentException(
                        "A FLAT_AMOUNT benefit carries no percentage");
                }
                if (flatAmount.signum() <= 0) {
                    throw new IllegalArgumentException("A flat benefit amount must be greater than"
                        + " zero -- a benefit that pays nothing is not a benefit");
                }
            }
        }
    }

    /** The common case: pays the whole cover. */
    public BenefitDefinition(BenefitType benefitType) {
        this(benefitType, BenefitCalculationMethod.SUM_ASSURED, null, null);
    }

    /**
     * What this benefit pays on a policy insuring {@code policySumAssured}.
     *
     * <p>The ONE place this arithmetic lives, exactly as {@link FrequencyLoading#applyTo} is.
     * Rounds once, at the end, HALF_UP to 2dp.
     */
    public BigDecimal amountFor(BigDecimal policySumAssured) {
        return switch (calculationMethod) {
            case SUM_ASSURED -> policySumAssured;
            case FLAT_AMOUNT -> flatAmount;
            case PERCENTAGE_OF_SUM_ASSURED ->
                policySumAssured.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
        };
    }
}
