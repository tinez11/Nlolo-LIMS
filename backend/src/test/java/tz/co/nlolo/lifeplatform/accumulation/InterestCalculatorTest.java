package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.accumulation.application.InterestCalculator;
import tz.co.nlolo.lifeplatform.accumulation.application.InterestCalculator.Movement;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class InterestCalculatorTest {

    private static final BigDecimal FIVE = new BigDecimal("5");

    @Test
    void aYearOfDailyFactorsCompoundsToTheDeclaredRateExactly() {
        // Before any rounding: (1 + f)^365 must be 1.05 to far better than a cent on any balance.
        BigDecimal f = InterestCalculator.dailyFactor(FIVE, 365);
        BigDecimal year = BigDecimal.ONE.add(f).pow(365, MathContext.DECIMAL128);
        assertThat(year.subtract(new BigDecimal("1.05")).abs()).isLessThan(new BigDecimal("1E-12"));
    }

    @Test
    void twelveMonthlyPostingsOnAConstantBalanceCreditTheDeclaredRate() {
        // The spec's test: a year at 5% credits 5%. Each month's interest is rounded to the cent and
        // then compounds, so the year can differ from 50,000.00 by the rounding alone -- at most a
        // cent a month, and in practice a cent or two.
        BigDecimal balance = new BigDecimal("1000000.00");
        BigDecimal credited = BigDecimal.ZERO;
        for (int m = 1; m <= 12; m++) {
            YearMonth month = YearMonth.of(2025, m);
            BigDecimal interest = InterestCalculator.interest(balance, List.of(), month.atDay(1), month.atEndOfMonth(),
                d -> FIVE).setScale(2, RoundingMode.HALF_EVEN);
            balance = balance.add(interest);
            credited = credited.add(interest);
        }
        assertThat(credited.doubleValue()).isCloseTo(50_000.00, within(0.05));
    }

    @Test
    void aDeclarationFromTheSixteenthSplitsTheMonthByDay() {
        LocalDate from = LocalDate.of(2025, 9, 1);
        LocalDate to = LocalDate.of(2025, 9, 30);
        LocalDate change = LocalDate.of(2025, 9, 16);
        BigDecimal opening = new BigDecimal("1000000.00");

        BigDecimal split = InterestCalculator.interest(opening, List.of(), from, to,
            d -> d.isBefore(change) ? new BigDecimal("3") : new BigDecimal("6"));

        // Fifteen days at 3% then fifteen at 6%, compounding through the change.
        BigDecimal f3 = InterestCalculator.dailyFactor(new BigDecimal("3"), 365);
        BigDecimal f6 = InterestCalculator.dailyFactor(new BigDecimal("6"), 365);
        BigDecimal expected = opening.multiply(BigDecimal.ONE.add(f3).pow(15, MathContext.DECIMAL128))
            .multiply(BigDecimal.ONE.add(f6).pow(15, MathContext.DECIMAL128)).subtract(opening);
        assertThat(split.subtract(expected).abs()).isLessThan(new BigDecimal("0.0001"));
    }

    @Test
    void aMovementEarnsFromTheDayItIsEffective() {
        LocalDate from = LocalDate.of(2025, 9, 1);
        LocalDate to = LocalDate.of(2025, 9, 30);
        // 100,000 arriving on the 30th earns exactly one day.
        BigDecimal interest = InterestCalculator.interest(BigDecimal.ZERO,
            List.of(new Movement(to, new BigDecimal("100000.00"))), from, to, d -> FIVE);
        assertThat(interest.subtract(new BigDecimal("100000.00").multiply(InterestCalculator.dailyFactor(FIVE, 365))).abs())
            .isLessThan(new BigDecimal("1E-10"));
    }

    @Test
    void aLeapYearDayUsesThreeHundredAndSixtySixDays() {
        LocalDate leapDay = LocalDate.of(2028, 2, 29);
        BigDecimal interest = InterestCalculator.interest(new BigDecimal("1000000.00"), List.of(), leapDay, leapDay, d -> FIVE);
        assertThat(interest.subtract(new BigDecimal("1000000.00").multiply(InterestCalculator.dailyFactor(FIVE, 366))).abs())
            .isLessThan(new BigDecimal("1E-10"));
    }

    @Test
    void aZeroBalanceEarnsNothing() {
        assertThat(InterestCalculator.interest(BigDecimal.ZERO, List.of(), LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 1, 31), d -> FIVE)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
