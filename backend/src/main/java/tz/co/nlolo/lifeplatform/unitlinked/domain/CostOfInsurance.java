package tz.co.nlolo.lifeplatform.unitlinked.domain;

import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.DeathRule;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The monthly cost of insurance (spec §6): the annual rate per 1,000 of the risk the insurer carries, over twelve,
 * rounded once. The risk is what death would pay beyond the fund under HIGHER_OF, and the whole sum assured under
 * SUM_ASSURED_PLUS_FUND.
 */
public final class CostOfInsurance {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);
    private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

    private CostOfInsurance() {}

    public static BigDecimal sumAtRisk(DeathRule rule, BigDecimal sumAssured, BigDecimal fundValue) {
        return rule == DeathRule.HIGHER_OF ? sumAssured.subtract(fundValue).max(BigDecimal.ZERO) : sumAssured;
    }

    public static BigDecimal monthly(BigDecimal annualRatePerMille, BigDecimal sumAtRisk) {
        return annualRatePerMille.multiply(sumAtRisk).divide(THOUSAND, 10, RoundingMode.HALF_EVEN)
            .divide(TWELVE, 2, RoundingMode.HALF_EVEN);
    }
}
