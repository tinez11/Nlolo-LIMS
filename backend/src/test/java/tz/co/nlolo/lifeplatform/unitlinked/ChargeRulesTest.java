package tz.co.nlolo.lifeplatform.unitlinked;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.product.api.UnitLinkedPlan.DeathRule;
import tz.co.nlolo.lifeplatform.unitlinked.domain.ChargeDates;
import tz.co.nlolo.lifeplatform.unitlinked.domain.CostOfInsurance;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The cost of insurance and the monthly charge dates (spec §6), in milliseconds. */
class ChargeRulesTest {

    private static BigDecimal d(String v) { return new BigDecimal(v); }

    @Test
    void higherOfChargesOnlyTheGapAndNothingOnceTheFundExceedsTheCover() {
        assertThat(CostOfInsurance.sumAtRisk(DeathRule.HIGHER_OF, d("10000000"), d("2500000"))).isEqualByComparingTo("7500000");
        assertThat(CostOfInsurance.sumAtRisk(DeathRule.HIGHER_OF, d("10000000"), d("12000000"))).isEqualByComparingTo("0");
    }

    @Test
    void sumAssuredPlusFundChargesTheWholeCover() {
        assertThat(CostOfInsurance.sumAtRisk(DeathRule.SUM_ASSURED_PLUS_FUND, d("10000000"), d("2500000")))
            .isEqualByComparingTo("10000000");
    }

    @Test
    void theMonthlyChargeIsTheAnnualRateOverTwelveRoundedOnce() {
        // 4.5 per mille a year on 7,500,000 = 33,750 a year = 2,812.50 a month
        assertThat(CostOfInsurance.monthly(d("4.5"), d("7500000"))).isEqualByComparingTo("2812.50");
        // 1.2 per mille on 1,000,001: 1,200.0012 a year = 100.0001 a month -> 100.00, rounded once at the end
        assertThat(CostOfInsurance.monthly(d("1.2"), d("1000001"))).isEqualByComparingTo("100.00");
    }

    @Test
    void chargeDatesStepFromEachDateInTurnAndNeverFallOnIssueDay() {
        LocalDate issued = LocalDate.of(2027, 1, 31);
        assertThat(ChargeDates.between(issued, issued, LocalDate.of(2027, 4, 30)))
            .containsExactly(LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 28), LocalDate.of(2027, 4, 28));
        assertThat(ChargeDates.between(issued, LocalDate.of(2027, 2, 28), LocalDate.of(2027, 3, 28)))
            .containsExactly(LocalDate.of(2027, 3, 28));
        assertThat(ChargeDates.between(issued, issued, issued)).isEmpty();
    }
}
