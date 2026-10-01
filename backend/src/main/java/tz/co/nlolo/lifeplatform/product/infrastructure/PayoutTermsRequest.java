package tz.co.nlolo.lifeplatform.product.infrastructure;

import tz.co.nlolo.lifeplatform.product.api.PayoutTerms;

import java.math.BigDecimal;

/** A version's free-look and payout servicing terms on the wire. See {@link PayoutRowRequest}. */
public record PayoutTermsRequest(Integer freeLookDays, Integer proofOfLifeIntervalMonths,
                                 Boolean survivalBenefitsDeductedFromDeath, BigDecimal deathBenefitPremiumPercent) {

    public PayoutTerms toTerms() {
        return new PayoutTerms(freeLookDays, proofOfLifeIntervalMonths, survivalBenefitsDeductedFromDeath,
            deathBenefitPremiumPercent);
    }
}
