package tz.co.nlolo.lifeplatform.policy.api;

/**
 * How a lender's agreed rate turns into the premium one loan costs.
 *
 * <p>Not the same question as what the rate is, and the platform answered only that one for a
 * long time. Both real client schedules price on the full disbursed amount, and they disagree
 * about everything else — so a single formula could not have matched both, and matched
 * neither.
 *
 * <p>The basis belongs to the SCHEME, beside the rate, for the same reason the rate does: it
 * is what one lender agreed, and two lenders on one filed product settle differently.
 */
public enum CreditLifePremiumBasis {

    /**
     * The rate, once, on the disbursed amount. Term does not enter the price.
     *
     * <p>Bumaco's basis. A 2-month loan and a 12-month loan of the same size cost the same,
     * which is not an oversight in their sheet: it holds across every loan on it.
     */
    FLAT_ON_PRINCIPAL,

    /**
     * The rate per annum, on the disbursed amount, multiplied by the term in years.
     *
     * <p>What this platform computed before any basis existed, and what the design spec
     * recorded as an assumption — "an agreed percent per year of the original loan amount,
     * charged once" — at a point where the client had not yet answered whether the percent was
     * per year or once. Kept because it is a coherent basis a lender may well agree, and
     * because every scheme written before this enum was priced on it.
     */
    PER_ANNUM_ON_PRINCIPAL,

    /**
     * The rate once per policy year, each year on the principal still outstanding at the start
     * of that year, declining straight-line across the term. Summed, and charged once.
     *
     * <p>LOLC's basis, and the reason their schedule carries a column per year out to a fifth.
     * Those columns are how the total is arrived at, not an instruction to invoice annually:
     * the whole figure is charged at enrolment like every other basis (client, 2026-09-29).
     */
    ANNUAL_ON_DECLINING_BALANCE
}
