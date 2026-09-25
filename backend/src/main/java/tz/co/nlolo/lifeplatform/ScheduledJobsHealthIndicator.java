package tz.co.nlolo.lifeplatform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Is everything installed that the numbered migrations cannot install?
 *
 * <p>The billing sweep, the commission close, offer expiry and reminders, loan interest, and
 * pg_partman's monthly partitions all live in {@code db-migrations/_post-migration}, installed by
 * {@code scripts/configure-db.sh}. For months nothing ran all of them, and nothing noticed: the
 * platform started and served traffic while commission could never be paid, loans never accrued
 * interest, and the ledgers ran out of partitions at the end of September 2026. A missing job
 * fails nothing -- the work just does not happen -- so this is the only place it can surface.
 *
 * <p>DOWN when anything expected is missing: an environment in that state is misconfigured,
 * whatever else is healthy. UNKNOWN when the readiness function itself is absent -- a database
 * {@code configure-db.sh} has never touched, which includes every Testcontainers database the
 * test suite builds; UNKNOWN ranks below UP, so those stay healthy overall.
 *
 * <p>The health endpoint shows no component details, so what is missing is also LOGGED, once, at
 * startup -- a DOWN with no reason is a page nobody can act on.
 */
@Component("scheduledJobs")
public class ScheduledJobsHealthIndicator implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobsHealthIndicator.class);
    static final String NOT_INSTALLED =
        "ops.platform_readiness() is not installed -- run scripts/configure-db.sh against this database";

    private final JdbcTemplate jdbcTemplate;

    public ScheduledJobsHealthIndicator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The readiness items that are NOT present, or null when the check cannot run at all. */
    List<String> missing() {
        try {
            return jdbcTemplate.query("SELECT item, present FROM ops.platform_readiness()",
                    (rs, i) -> rs.getBoolean("present") ? null : rs.getString("item"))
                .stream().filter(item -> item != null).toList();
        } catch (DataAccessException e) {
            return null;
        }
    }

    @Override
    public Health health() {
        List<String> missing = missing();
        if (missing == null) {
            return Health.unknown().withDetail("reason", NOT_INSTALLED).build();
        }
        if (!missing.isEmpty()) {
            return Health.down().withDetail("missing", missing)
                .withDetail("fix", "run scripts/configure-db.sh against this database").build();
        }
        return Health.up().build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void reportAtStartup() {
        List<String> missing = missing();
        if (missing == null) {
            log.warn("Scheduled jobs and partitions cannot be checked: {}", NOT_INSTALLED);
        } else if (!missing.isEmpty()) {
            log.warn("NOT INSTALLED on this database, so this work will silently never happen: {}. "
                + "Run scripts/configure-db.sh.", String.join(", ", missing));
        } else {
            log.info("Scheduled jobs and partition maintenance: all installed");
        }
    }
}
