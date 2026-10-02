package tz.co.nlolo.lifeplatform.benefitpayout;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.ScheduleExpander;
import tz.co.nlolo.lifeplatform.benefitpayout.domain.ScheduleExpander.Planned;
import tz.co.nlolo.lifeplatform.product.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Turning a product's authored rows into one policy's dated amounts -- the guide's "calculated and
 * stored on the day the policy is issued" (§16).
 */
class ScheduleExpanderTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 15);
    private static final LocalDate MATURITY = LocalDate.of(2046, 1, 15);
    private static final BigDecimal SA = new BigDecimal("1000000.00");

    private static PayoutPlan plan(PayoutRowInput... rows) {
        return PayoutPlan.authored(new PayoutTerms(15, 12, false, null), List.of(rows));
    }

    @Test
    void anAnnualSurvivalRowPaysOnTheAnniversaryOfEachOfItsYears() {
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.SURVIVAL, 5, 5, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("10"), PayoutFrequency.ANNUAL)),
            START, MATURITY, SA);
        assertThat(out).containsExactly(
            new Planned(PayoutKind.SURVIVAL, 0, LocalDate.of(2031, 1, 15), new BigDecimal("100000.00")));
    }

    @Test
    void aMonthlyIncomeRowPaysTwelveInstalmentsPerYearEndingOnTheAnniversary() {
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.INCOME, 6, 7, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("3"), PayoutFrequency.MONTHLY)),
            START, MATURITY, SA);
        assertThat(out).hasSize(24);
        // Policy year 6 runs from start+5y; its first monthly instalment is a month into that year
        // and its twelfth lands exactly on the next anniversary.
        assertThat(out.get(0).dueDate()).isEqualTo(LocalDate.of(2031, 2, 15));
        assertThat(out.get(11).dueDate()).isEqualTo(LocalDate.of(2032, 1, 15));
        assertThat(out.get(0).amount()).isEqualByComparingTo("2500.00");
        // 3% of a million, split twelve ways, still sums to the year.
        assertThat(out.subList(0, 12).stream().map(Planned::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
            .isEqualByComparingTo("30000.00");
    }

    @Test
    void maturityPaysOnTheMaturityDateAndAPremiumReturnHasNoAmountYet() {
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.MATURITY, null, null, PayoutAmountBasis.PERCENT_OF_SA, new BigDecimal("100"), null),
            new PayoutRowInput(PayoutKind.RETURN_OF_PREMIUM, null, null, PayoutAmountBasis.PERCENT_OF_PREMIUMS, new BigDecimal("100"), null)),
            START, MATURITY, SA);
        // Null on the premium return, because premiums collected is not knowable at issue.
        assertThat(out).containsExactly(
            new Planned(PayoutKind.MATURITY, 0, MATURITY, new BigDecimal("1000000.00")),
            new Planned(PayoutKind.RETURN_OF_PREMIUM, 1, MATURITY, null));
    }

    @Test
    void instalmentsAfterTheMaturityDateAreDropped() {
        // A row authored past the term of this particular policy: the product does not fix the
        // term, so a 25-year row on a 20-year contract is the author's range meeting this
        // policy's end, not a mistake.
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.SURVIVAL, 18, 25, PayoutAmountBasis.FIXED, new BigDecimal("5000"), PayoutFrequency.ANNUAL)),
            START, MATURITY, SA);
        assertThat(out).extracting(Planned::dueDate).containsExactly(
            LocalDate.of(2044, 1, 15), LocalDate.of(2045, 1, 15), LocalDate.of(2046, 1, 15));
    }

    @Test
    void aFixedAmountIsNotScaledBySumAssured() {
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.SURVIVAL, 2, 2, PayoutAmountBasis.FIXED, new BigDecimal("5000"), PayoutFrequency.ANNUAL)),
            START, MATURITY, SA);
        assertThat(out.get(0).amount()).isEqualByComparingTo("5000.00");
    }

    @Test
    void aPolicyWithNoMaturityDateKeepsEveryAuthoredYear() {
        // Whole life terms at null; nothing should be dropped for running past a date there is none of.
        List<Planned> out = ScheduleExpander.expand(plan(
            new PayoutRowInput(PayoutKind.INCOME, 1, 2, PayoutAmountBasis.FIXED, new BigDecimal("1200"), PayoutFrequency.QUARTERLY)),
            START, null, SA);
        assertThat(out).hasSize(8);
    }

    @Test
    void anUnauthoredPlanExpandsToNothing() {
        assertThat(ScheduleExpander.expand(PayoutPlan.none(), START, MATURITY, SA)).isEmpty();
    }

    @Test
    void anAccountValueMaturityIsExpandedWithNoAmount() {
        // Product step 3: the account's value is not known until the day it falls due -- a figure
        // written here off the sum assured would be a second, wrong answer to "what does it pay".
        PayoutPlan plan = PayoutPlan.authored(new PayoutTerms(15, null, null, null), List.of(new PayoutRowInput(
            PayoutKind.MATURITY, null, null, PayoutAmountBasis.ACCOUNT_VALUE, new BigDecimal("100"), null)));
        assertThat(ScheduleExpander.expand(plan, START, MATURITY, SA)).singleElement()
            .satisfies(p -> assertThat(p.amount()).isNull());
    }
}
