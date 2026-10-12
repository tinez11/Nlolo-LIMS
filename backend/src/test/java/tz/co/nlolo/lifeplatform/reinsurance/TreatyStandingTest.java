package tz.co.nlolo.lifeplatform.reinsurance;

import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyStanding;
import tz.co.nlolo.lifeplatform.reinsurance.api.TreatyType;
import tz.co.nlolo.lifeplatform.reinsurance.domain.ReinsuranceTreaty;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A treaty's standing is read from its dates, and agrees with {@code isActiveOn} -- the check that
 * actually decides ceding -- on every day. A treaty listed as "in force" that cession selection
 * would refuse, or the reverse, is the kind of disagreement this exists to rule out.
 */
class TreatyStandingTest {

    private static ReinsuranceTreaty treaty(LocalDate from, LocalDate to) {
        return new ReinsuranceTreaty(UUID.randomUUID(), "Test Re", TreatyType.QUOTA_SHARE,
            BigDecimal.ZERO, "TZS", new BigDecimal("30.00"), from, to, "actuary");
    }

    @Test
    void aTreatyWhoseStartIsStillToComeHasNotStarted() {
        // The case that surfaced this: a treaty for 22-29 October showed "Active" on 13 October.
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 29));
        assertThat(t.standingOn(LocalDate.of(2026, 10, 13))).isEqualTo(TreatyStanding.NOT_STARTED);
    }

    @Test
    void itIsInForceFromItsFirstDayToItsLastInclusive() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 29));
        assertThat(t.standingOn(LocalDate.of(2026, 10, 22))).isEqualTo(TreatyStanding.IN_FORCE);
        assertThat(t.standingOn(LocalDate.of(2026, 10, 29))).isEqualTo(TreatyStanding.IN_FORCE);
    }

    @Test
    void itHasEndedTheDayAfterItsEffectiveTo() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 29));
        assertThat(t.standingOn(LocalDate.of(2026, 10, 30))).isEqualTo(TreatyStanding.ENDED);
    }

    @Test
    void anOpenEndedTreatyStaysInForce() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), null);
        assertThat(t.standingOn(LocalDate.of(2099, 1, 1))).isEqualTo(TreatyStanding.IN_FORCE);
    }

    @Test
    void aWithdrawnTreatyHasEndedWhateverItsDates() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 1, 1), null);
        t.expire("actuary");
        assertThat(t.standingOn(LocalDate.of(2026, 6, 1))).isEqualTo(TreatyStanding.ENDED);
    }

    @Test
    void inForceMeansExactlyWhatCessionSelectionAccepts() {
        ReinsuranceTreaty t = treaty(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 29));
        for (LocalDate day = LocalDate.of(2026, 10, 15); day.isBefore(LocalDate.of(2026, 11, 5)); day = day.plusDays(1)) {
            assertThat(t.standingOn(day) == TreatyStanding.IN_FORCE)
                .as("standing and isActiveOn must agree on %s", day)
                .isEqualTo(t.isActiveOn(day));
        }
    }
}
