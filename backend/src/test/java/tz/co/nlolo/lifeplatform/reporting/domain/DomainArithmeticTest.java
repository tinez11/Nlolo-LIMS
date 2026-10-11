package tz.co.nlolo.lifeplatform.reporting.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;

class DomainArithmeticTest {

    @Test
    void annualisedPremiumMultipliesByInstalmentsAndRefusesSingleAndUnknown() {
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "MONTHLY")).contains(new BigDecimal("12000"));
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "QUARTERLY")).contains(new BigDecimal("4000"));
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "ANNUALLY")).contains(new BigDecimal("1000"));
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "SINGLE")).isEmpty();
        assertThat(AnnualisedPremium.of(new BigDecimal("1000"), "FORTNIGHTLY")).isEmpty();
        assertThat(AnnualisedPremium.isSingle("SINGLE")).isTrue();
        assertThat(AnnualisedPremium.isRecognised("FORTNIGHTLY")).isFalse();
    }

    @Test
    void aLateUtcEveningIsTheNextTanzanianDay() {
        assertThat(CivilDates.of(Instant.parse("2026-10-10T22:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 11));
        assertThat(CivilDates.of(Instant.parse("2026-10-10T20:59:59Z"))).isEqualTo(LocalDate.of(2026, 10, 10));
    }

    @Test
    void ageBandsAreAgeOnTheAsOfDate() {
        LocalDate asOf = LocalDate.of(2026, 10, 11);
        assertThat(AgeBands.of(LocalDate.of(2008, 10, 12), asOf)).isEqualTo("0-17");
        assertThat(AgeBands.of(LocalDate.of(2008, 10, 11), asOf)).isEqualTo("18-24");
        assertThat(AgeBands.of(LocalDate.of(1990, 1, 1), asOf)).isEqualTo("35-44");
        assertThat(AgeBands.of(LocalDate.of(1950, 1, 1), asOf)).isEqualTo("65+");
        assertThat(AgeBands.of(null, asOf)).isEqualTo("Unknown");
        assertThat(AgeBands.ORDER).containsExactly("0-17", "18-24", "25-34", "35-44", "45-54", "55-64", "65+", "Unknown");
    }

    @Test
    void persistencyMonthIsMeasuredAtTheStartOfThatMonthAndOnlyOnceTheWholeCohortReachedIt() {
        assertThat(PersistencyMonths.measuredOn(LocalDate.of(2024, 3, 15), 13)).isEqualTo(LocalDate.of(2025, 3, 15));
        assertThat(PersistencyMonths.reached(YearMonth.of(2024, 3), 13, LocalDate.of(2025, 3, 31))).isTrue();
        assertThat(PersistencyMonths.reached(YearMonth.of(2024, 3), 13, LocalDate.of(2025, 3, 30))).isFalse();
        assertThat(PersistencyMonths.MONTHS).containsExactly(13, 25, 37, 61);
    }

    @Test
    void figuresAreStringsWithFixedScale() {
        assertThat(Figures.money(new BigDecimal("1234.5"))).isEqualTo("1234.50");
        assertThat(Figures.money(null)).isEqualTo("0.00");
        assertThat(Figures.count(7)).isEqualTo("7");
        assertThat(Figures.percent(new BigDecimal("1"), new BigDecimal("3"))).isEqualTo("33.33");
        assertThat(Figures.percent(BigDecimal.ONE, BigDecimal.ZERO)).isEqualTo("");
    }
}
