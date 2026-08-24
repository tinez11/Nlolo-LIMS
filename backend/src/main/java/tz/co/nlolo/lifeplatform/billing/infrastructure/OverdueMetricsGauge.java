package tz.co.nlolo.lifeplatform.billing.infrastructure;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backs the observability/alert-rules.yml FieldReceiptReconciliationOverdue alert's
 * lifeplatform_field_receipt_overdue_count gauge. Micrometer gauges are pull-based -- this
 * value is computed lazily on each Prometheus scrape from `value`, which this class refreshes
 * from the database on a short, fixed interval. billing.overdue_metrics_snapshot is deliberately
 * NOT tenant-scoped (same class of decision as refdata.reference_code_set) and app_role has
 * SELECT-only privilege on it (Task 1's billing/V2 migration REVOKEs INSERT/UPDATE/DELETE) --
 * only billing.sweep_billing_state() (SECURITY DEFINER) ever writes it.
 */
@Component
public class OverdueMetricsGauge {

    private static final Logger log = LoggerFactory.getLogger(OverdueMetricsGauge.class);

    private final JdbcTemplate jdbcTemplate;
    private final AtomicInteger value = new AtomicInteger(0);

    public OverdueMetricsGauge(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        meterRegistry.gauge("lifeplatform_field_receipt_overdue_count", value);
    }

    // A transient scrape-source failure (e.g. a DB blip, or -- observed empirically in this
    // test suite -- a cached Spring test ApplicationContext outliving its own Testcontainers
    // Postgres) should degrade to serving the last-known-good value, not spam ERROR logs on
    // every tick; a monitoring gauge that itself pages on a hiccup defeats the point of one.
    @Scheduled(fixedRate = 60000)
    void refresh() {
        try {
            value.set(jdbcTemplate.queryForObject(
                "SELECT field_receipt_overdue_count FROM billing.overdue_metrics_snapshot WHERE id = 1", Integer.class));
        } catch (DataAccessException e) {
            log.debug("Skipping overdue-metrics refresh -- database unreachable", e);
        }
    }
}
