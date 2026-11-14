package tz.co.nlolo.lifeplatform;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.CompletedEventPublications;
import org.springframework.modulith.events.IncompleteEventPublications;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Delivers again what a listener did not finish (2026-11-14, durable events; the user chose automatic retry). Every
 * ten minutes, each event delivery recorded more than five minutes ago and not marked complete -- the backend stopped
 * mid-delivery, or the listener failed -- is handed to its listener again. The listener runs in a transaction of its
 * own, so a failed attempt left nothing behind; one that keeps failing is tried every ten minutes and logged each time,
 * and the count of deliveries stuck over an hour is on /actuator/prometheus for an alert. The backend's start-up also
 * delivers every incomplete one once (spring.modulith.republish-outstanding-events-on-restart).
 *
 * <p>Completed rows are kept seven days, then deleted.
 */
@Component
public class EventRedelivery {

    private static final Logger log = LoggerFactory.getLogger(EventRedelivery.class);
    static final String STUCK_GAUGE = "lifeplatform_event_deliveries_stuck";

    private final IncompleteEventPublications incomplete;
    private final CompletedEventPublications completed;
    private final JdbcTemplate jdbc;

    public EventRedelivery(IncompleteEventPublications incomplete, CompletedEventPublications completed,
                           JdbcTemplate jdbc, MeterRegistry meters) {
        this.incomplete = incomplete;
        this.completed = completed;
        this.jdbc = jdbc;
        Gauge.builder(STUCK_GAUGE, this, EventRedelivery::stuckOverAnHour)
            .description("Event deliveries to a listener not completed after an hour")
            .register(meters);
    }

    @Scheduled(fixedDelayString = "${app.events.redelivery-interval-ms:600000}",
        initialDelayString = "${app.events.redelivery-interval-ms:600000}")
    public void redeliver() {
        long waiting = waitingOverFiveMinutes();
        if (waiting == 0) return;
        log.warn("Delivering {} unfinished event deliveries again", waiting);
        incomplete.resubmitIncompletePublicationsOlderThan(Duration.ofMinutes(5));
    }

    @Scheduled(cron = "${app.events.cleanup-cron:0 30 2 * * *}", zone = "Africa/Dar_es_Salaam")
    public void forgetCompleted() {
        completed.deletePublicationsOlderThan(Duration.ofDays(7));
    }

    long waitingOverFiveMinutes() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL"
            + " AND publication_date < now() - interval '5 minutes'", Long.class);
        return n == null ? 0 : n;
    }

    double stuckOverAnHour() {
        try {
            Long n = jdbc.queryForObject("SELECT count(*) FROM event_publication WHERE completion_date IS NULL"
                + " AND publication_date < now() - interval '1 hour'", Long.class);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
