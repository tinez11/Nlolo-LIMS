package tz.co.nlolo.lifeplatform.product.api;

/**
 * What attached bonuses add to a surrender (product step 4, Q6). There is NO default: the user's
 * rule is that the sum assured's cash-value factor is never assumed to apply to bonuses unless the
 * version says so. NONE adds nothing; SUM_ASSURED_SCALE applies step 1's factor for the completed
 * years, explicitly chosen; OWN_SCALE reads the version's own per-mille rows.
 */
public enum BonusSurrenderBasis { NONE, SUM_ASSURED_SCALE, OWN_SCALE }
