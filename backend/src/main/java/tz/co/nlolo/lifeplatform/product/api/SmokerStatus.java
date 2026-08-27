package tz.co.nlolo.lifeplatform.product.api;

/**
 * A rating dimension of the base rate table.
 *
 * `UNKNOWN` is a real, ratable value rather than a null stand-in: a product may
 * price undeclared smoker status deliberately (typically at the smoker rate, or
 * at a loading between the two), and an actuary must be able to author that cell.
 * Treating it as absent would instead push the decision into code as a fallback,
 * which is how {@code resolveRatingMultiplier}'s neutral-1.0 became a silent
 * mispricing risk on the pricing path.
 *
 * The concept already existed as {@link FactorType#SMOKER_STATUS} — a rating
 * factor with no enumerated values. This names the values.
 *
 * Like {@link Sex}, no party record carries it, so a quote asserts it.
 */
public enum SmokerStatus {
    SMOKER,
    NON_SMOKER,
    UNKNOWN
}
