package tz.co.nlolo.lifeplatform.audit.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes one activity row (2026-11-14), in a transaction of its own: whatever the request did -- done, refused,
 * failed and rolled back -- its record is kept. Never fails the person's request: by now it has been answered.
 */
@Component
public class ActivityRecorder {

    private static final Logger log = LoggerFactory.getLogger(ActivityRecorder.class);

    public record Row(UUID activityId, UUID tenantId, Instant occurredAt, String actorSubject, String actorName,
                      String actorKind, String method, String route, String label, String kind, String recordType,
                      String recordId, String recordLabel, BigDecimal amount, String currency, String bodyJson,
                      String outcome, int statusCode, String reason) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate own;

    public ActivityRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.own = new TransactionTemplate(transactions);
        this.own.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(Row r) {
        try {
            own.executeWithoutResult(status -> jdbc.update("""
                INSERT INTO audit.activity (activity_id, tenant_id, occurred_at, actor_subject, actor_name, actor_kind,
                  http_method, route, label, kind, record_type, record_id, record_label, amount, currency, body,
                  outcome, status_code, reason)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?)""",
                r.activityId(), r.tenantId(), Timestamp.from(r.occurredAt()), cut(r.actorSubject(), 100),
                cut(r.actorName(), 200), r.actorKind(), r.method(), cut(r.route(), 300), cut(r.label(), 200), r.kind(),
                cut(r.recordType(), 40), cut(r.recordId(), 100), cut(r.recordLabel(), 200), r.amount(), r.currency(),
                r.bodyJson(), r.outcome(), r.statusCode(), r.reason()));
        } catch (RuntimeException e) {
            // Loud, so a gap in the log is seen -- and nothing else: the request it records has already been answered.
            log.error("Could not record activity {} {} for tenant {}", r.method(), r.route(), r.tenantId(), e);
        }
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
