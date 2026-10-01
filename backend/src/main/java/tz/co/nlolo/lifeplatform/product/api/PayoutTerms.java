package tz.co.nlolo.lifeplatform.product.api;

import java.math.BigDecimal;

/**
 * A version's servicing terms for payouts and free-look.
 *
 * <p>Every field is nullable here and required conditionally by {@link
 * tz.co.nlolo.lifeplatform.product.domain.PayoutPlanValidator}: free-look days on an individual
 * product, a proof-of-life interval once an INCOME row exists, and the death-benefit rules only
 * where the rows that need them are present. A CHECK cannot ask what category a product is.
 *
 * @param freeLookDays how long after issue the customer may cancel and be refunded (guide §21.3)
 * @param proofOfLifeIntervalMonths how often an income stream must re-prove the life assured lives
 * @param survivalBenefitsDeductedFromDeath whether survival benefits already paid come off the
 *     death benefit -- a product setting, not a universal rule (guide §7)
 * @param deathBenefitPremiumPercent when set, the death benefit is the higher of the sum assured
 *     and this percentage of premiums collected (guide §6)
 */
public record PayoutTerms(Integer freeLookDays, Integer proofOfLifeIntervalMonths,
                          Boolean survivalBenefitsDeductedFromDeath, BigDecimal deathBenefitPremiumPercent) {

    public static PayoutTerms none() { return new PayoutTerms(null, null, null, null); }
}
