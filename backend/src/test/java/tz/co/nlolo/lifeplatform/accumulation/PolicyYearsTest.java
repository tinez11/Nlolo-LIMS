package tz.co.nlolo.lifeplatform.accumulation;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.accumulation.application.PolicyYears;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyYearsTest {

    private static final LocalDate START = LocalDate.of(2024, 3, 15);

    @Test
    void theFirstYearRunsToTheDayBeforeTheFirstAnniversary() {
        assertThat(PolicyYears.of(START, START)).isEqualTo(1);
        assertThat(PolicyYears.of(START, LocalDate.of(2025, 3, 14))).isEqualTo(1);
        assertThat(PolicyYears.of(START, LocalDate.of(2025, 3, 15))).isEqualTo(2);
    }

    @Test
    void aLeapDayCommencementHasItsAnniversaryOnTheLastDayOfFebruary() {
        // Period.between's convention, and the one benefitpayout's schedule uses: 29 Feb + 1 year
        // = 28 Feb. Two modules counting policy years two ways would charge a year-1 fee on a
        // year-2 date.
        LocalDate leap = LocalDate.of(2024, 2, 29);
        assertThat(PolicyYears.of(leap, LocalDate.of(2025, 2, 27))).isEqualTo(1);
        assertThat(PolicyYears.of(leap, LocalDate.of(2025, 2, 28))).isEqualTo(2);
    }

    @Test
    void aDateBeforeCoverIsRefused() {
        assertThatThrownBy(() -> PolicyYears.of(START, START.minusDays(1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("2024-03-14 is before cover began on 2024-03-15");
    }
}
