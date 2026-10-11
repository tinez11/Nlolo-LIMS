package tz.co.nlolo.lifeplatform.reporting.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Applies one event in its own transaction, as its own tenant. Shared by the live listener and the rebuild
 * so both take exactly one path.
 *
 * <p>REQUIRES_NEW, through a TransactionTemplate: from an AFTER_COMMIT callback a plain @Transactional
 * silently joins the producer's already-committed transaction and never commits (regreporting's
 * PolicyEventListener records finding this the hard way). The tenant is set BEFORE the transaction opens,
 * because TenantAwareDataSource sets the RLS variable when the connection is borrowed.
 *
 * <p>A failure is counted and logged, never thrown: a reporting bug must not fail the business transaction
 * that published the event, and the rebuild is the documented way to repair a missed event.
 */
@Component
public class EventApplier {

    public enum Outcome { APPLIED, DUPLICATE, IGNORED, FAILED }

    static final String FAILED_COUNTER = "lifeplatform_reporting_event_processing_failed_total";
    private static final Logger log = LoggerFactory.getLogger(EventApplier.class);

    private final ReportingEventHandlers handlers;
    private final MeterRegistry meters;
    private final TransactionTemplate requiresNew;

    public EventApplier(ReportingEventHandlers handlers, MeterRegistry meters, PlatformTransactionManager transactionManager) {
        this.handlers = handlers;
        this.meters = meters;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Outcome apply(UUID tenantId, UUID eventId, String eventType, Instant occurredAt, Map<String, Object> payload) {
        if (!handlers.handles(eventType)) {
            return Outcome.IGNORED;
        }
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            Boolean applied = requiresNew.execute(status -> handlers.apply(tenantId, eventId, eventType, occurredAt, payload));
            return Boolean.TRUE.equals(applied) ? Outcome.APPLIED : Outcome.DUPLICATE;
        } catch (RuntimeException e) {
            meters.counter(FAILED_COUNTER, "eventType", eventType).increment();
            log.error("reporting failed to apply {} {} for tenant {}", eventType, eventId, tenantId, e);
            return Outcome.FAILED;
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
