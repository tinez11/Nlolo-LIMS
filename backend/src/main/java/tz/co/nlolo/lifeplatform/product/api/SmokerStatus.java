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
 * <p>Reachable from BOTH paths. A quote asserts it, and automatic issuance maps a life whose
 * smoker status was never recorded onto {@code UNKNOWN} — so a product that deliberately priced
 * the undeclared case gets used, and one that did not still refuses, naming the reason.
 *
 * <p>It was unreachable from issuance until then: an unrecorded status was passed as null, and
 * {@code resolveBaseRatePerMille} returns empty for a null, so the cell could be authored, shown
 * on the product screen, and price nothing. Unlike {@link Sex}, which keeps refusing when
 * unrecorded because a neutral sex would be a unisex rate — a different actuarial object.
 */
public enum SmokerStatus {
    SMOKER,
    NON_SMOKER,
    UNKNOWN
}
