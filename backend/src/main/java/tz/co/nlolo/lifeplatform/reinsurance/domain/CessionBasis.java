package tz.co.nlolo.lifeplatform.reinsurance.domain;

/**
 * What of a policy a treaty takes, by its product category (2026-11-12, finance finding 166, the user's "okay"). Life
 * reinsurance takes the sum at risk and the premium for that risk -- never the savings part, which buys no cover.
 *
 * <ul>
 *   <li>{@link #PREMIUM} -- protection: term, funeral, credit and group life. The whole premium pays for cover, so the
 *       treaty's share of it is ceded, as before.</li>
 *   <li>{@link #COST_OF_INSURANCE} -- unit-linked. The risk is the sum assured beyond the fund, and its premium is the
 *       cost of insurance the monthly charge run takes from the units; each bordereau charges the treaty's share of
 *       what was actually taken that month.</li>
 *   <li>{@link #NOT_CEDED} -- annuities (no death risk to reinsure: a death ends the payments), savings and deposits
 *       (death pays the account value), and traditional endowment and whole life. For the last two the platform holds
 *       no figure for the risk part of each premium -- that takes a reserve or a risk rate from an actuary -- so
 *       they are not ceded until one is given (the user's choice, 2026-11-13).</li>
 * </ul>
 * A projection from before categories were recorded (null) keeps the old basis rather than silently stopping.
 */
public enum CessionBasis {
    PREMIUM, COST_OF_INSURANCE, NOT_CEDED;

    public static CessionBasis of(String productCategory) {
        if (productCategory == null) {
            return PREMIUM;
        }
        return switch (productCategory) {
            case "UNIT_LINKED" -> COST_OF_INSURANCE;
            case "ANNUITY", "ENDOWMENT", "WHOLE_LIFE", "EDUCATION_SAVINGS" -> NOT_CEDED;
            default -> PREMIUM;
        };
    }
}
