package tz.co.nlolo.lifeplatform.reporting.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class StatusTimelineTest {

    private static StatusTimeline.Change change(String date, String at, PolicyStatus status) {
        return new StatusTimeline.Change(LocalDate.parse(date), Instant.parse(at), UUID.randomUUID(), status);
    }

    @Test
    void theLatestChangeOnOrBeforeTheDateWins() {
        StatusTimeline t = new StatusTimeline(List.of(
            change("2025-03-01", "2025-03-01T08:00:00Z", PolicyStatus.LAPSED),     // given out of order
            change("2025-01-10", "2025-01-10T08:00:00Z", PolicyStatus.ACTIVE),
            change("2025-04-15", "2025-04-15T08:00:00Z", PolicyStatus.ACTIVE)));   // reinstated
        assertThat(t.statusOn(LocalDate.parse("2025-01-09"))).isEmpty();
        assertThat(t.inForceOn(LocalDate.parse("2025-01-10"))).isTrue();
        assertThat(t.inForceOn(LocalDate.parse("2025-03-01"))).isFalse();
        assertThat(t.inForceOn(LocalDate.parse("2025-04-15"))).isTrue();
    }

    @Test
    void sameDayChangesAreOrderedByTheirTimestamps() {
        StatusTimeline t = new StatusTimeline(List.of(
            change("2025-06-01", "2025-06-01T15:00:00Z", PolicyStatus.SURRENDERED),
            change("2025-06-01", "2025-06-01T09:00:00Z", PolicyStatus.ACTIVE)));
        assertThat(t.statusOn(LocalDate.parse("2025-06-01"))).contains(PolicyStatus.SURRENDERED);
    }

    @Test
    void suspendedAndPaidUpAreInForce() {
        assertThat(PolicyStatus.SUSPENDED.inForce()).isTrue();
        assertThat(PolicyStatus.PAID_UP.inForce()).isTrue();
        assertThat(PolicyStatus.LAPSED.inForce()).isFalse();
        assertThat(new StatusTimeline(List.of(change("2025-01-01", "2025-01-01T00:00:00Z",
            PolicyStatus.CANCELLED_FREE_LOOK))).ever(PolicyStatus.CANCELLED_FREE_LOOK)).isTrue();
    }
}
