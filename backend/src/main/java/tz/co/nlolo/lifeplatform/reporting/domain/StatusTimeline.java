package tz.co.nlolo.lifeplatform.reporting.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One policy's status history. Ordered exactly as the SQL in ReportSql orders it -- effective date, then the
 * event's timestamp, then the event id as text (Postgres orders uuid bytewise, which is the order of the
 * lower-case hex string) -- so a Java answer and a SQL answer never disagree on a tie.
 */
public final class StatusTimeline {

    public record Change(LocalDate effectiveOn, Instant occurredAt, UUID eventId, PolicyStatus status) {}

    private static final Comparator<Change> ORDER = Comparator.comparing(Change::effectiveOn)
        .thenComparing(Change::occurredAt).thenComparing(c -> c.eventId().toString());

    private final List<Change> changes;

    public StatusTimeline(Collection<Change> changes) {
        this.changes = changes.stream().sorted(ORDER).toList();
    }

    public Optional<PolicyStatus> statusOn(LocalDate date) {
        PolicyStatus status = null;
        for (Change c : changes) {
            if (c.effectiveOn().isAfter(date)) break;
            status = c.status();
        }
        return Optional.ofNullable(status);
    }

    public boolean inForceOn(LocalDate date) {
        return statusOn(date).map(PolicyStatus::inForce).orElse(false);
    }

    public boolean ever(PolicyStatus status) {
        return changes.stream().anyMatch(c -> c.status() == status);
    }
}
