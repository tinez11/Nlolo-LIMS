package tz.co.nlolo.lifeplatform.unitlinked;

import org.junit.jupiter.api.Test;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/** The forward-pricing invariant, in milliseconds: no order can be bound to a price that already existed. */
class BindingRuleTest {

    private static final LocalTime CUT_OFF = LocalTime.of(14, 0);

    private static Instant eat(String local) {
        return LocalDateTime.parse(local).atZone(ZoneId.of("Africa/Dar_es_Salaam")).toInstant();
    }

    @Test
    void beforeTheCutOffBindsToToday() {
        assertThat(BindingRule.boundDate(eat("2026-11-02T13:59:59"), CUT_OFF)).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void atOrAfterTheCutOffBindsToTomorrow() {
        assertThat(BindingRule.boundDate(eat("2026-11-02T14:00:00"), CUT_OFF)).isEqualTo(LocalDate.of(2026, 11, 3));
        assertThat(BindingRule.boundDate(eat("2026-11-02T23:59:59"), CUT_OFF)).isEqualTo(LocalDate.of(2026, 11, 3));
    }

    @Test
    void justAfterMidnightEatIsThatCivilDayNotYesterdayInUtc() {
        // 00:30 EAT is 21:30 UTC the day before -- the UTC/civil-day trap.
        assertThat(BindingRule.boundDate(eat("2026-11-02T00:30:00"), CUT_OFF)).isEqualTo(LocalDate.of(2026, 11, 2));
    }

    @Test
    void aPriceCannotBeApprovedBeforeItsDatesCutOff() {
        assertThat(BindingRule.earliestApproval(LocalDate.of(2026, 11, 2), CUT_OFF)).isEqualTo(eat("2026-11-02T14:00:00"));
    }

    @Test
    void noOrderCanBeBoundToADatesPriceThatWasApprovableWhenItArrived() {
        for (String received : new String[] {"2026-11-02T00:00:00", "2026-11-02T13:59:59", "2026-11-02T14:00:00",
                                             "2026-11-02T23:59:59"}) {
            Instant at = eat(received);
            LocalDate bound = BindingRule.boundDate(at, CUT_OFF);
            assertThat(BindingRule.earliestApproval(bound, CUT_OFF)).as(received).isAfter(at);
        }
    }
}
