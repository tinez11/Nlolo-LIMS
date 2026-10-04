package tz.co.nlolo.lifeplatform.policy;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.policy.domain.InstalmentDates;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The next premium date, stepped as billing steps (plan R4). */
class InstalmentDatesTest {

    private static final LocalDate ISSUED = LocalDate.of(2026, 1, 15);

    @Test
    void monthlyIsTheNextFifteenthAfterTheDay() {
        assertThat(InstalmentDates.nextAfter(ISSUED, "MONTHLY", ISSUED)).isEqualTo(LocalDate.of(2026, 2, 15));
        assertThat(InstalmentDates.nextAfter(ISSUED, "MONTHLY", LocalDate.of(2026, 3, 2))).isEqualTo(LocalDate.of(2026, 3, 15));
    }

    @Test
    void aDayThatIsItselfAnInstalmentDateGivesTheOneAfter() {
        assertThat(InstalmentDates.nextAfter(ISSUED, "MONTHLY", LocalDate.of(2026, 3, 15))).isEqualTo(LocalDate.of(2026, 4, 15));
    }

    @Test
    void quarterlyAndAnnually() {
        assertThat(InstalmentDates.nextAfter(ISSUED, "QUARTERLY", LocalDate.of(2026, 5, 1))).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(InstalmentDates.nextAfter(ISSUED, "ANNUALLY", LocalDate.of(2026, 5, 1))).isEqualTo(LocalDate.of(2027, 1, 15));
    }

    @Test
    void aMonthEndIssueDriftsAsBillingsDatesDo() {
        // Billing steps from each due date: 31 Jan, 28 Feb, 28 Mar.
        LocalDate monthEnd = LocalDate.of(2026, 1, 31);
        assertThat(InstalmentDates.nextAfter(monthEnd, "MONTHLY", LocalDate.of(2026, 3, 1))).isEqualTo(LocalDate.of(2026, 3, 28));
    }

    @Test
    void aSinglePremiumHasNoInstalments() {
        assertThatThrownBy(() -> InstalmentDates.nextAfter(ISSUED, "SINGLE", ISSUED))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
