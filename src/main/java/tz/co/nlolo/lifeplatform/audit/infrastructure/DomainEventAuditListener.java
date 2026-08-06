package tz.co.nlolo.lifeplatform.audit.infrastructure;

import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.audit.domain.AuditLogEntry;
import tz.co.nlolo.lifeplatform.audit.domain.FailedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

/**
 * Generic listener for every DomainEventEnvelope<?> published platform-wide
 * (docs/02-module-architecture.md §3.18) -- keyed on the envelope type, not on
 * per-event classes, so audit needs no dependency edge to any producer module.
 * AFTER_COMMIT per Deliverable 2 Rev 2 §2's mandatory transactional event
 * publishing convention: an event is only durable here once the producer's
 * own state change has committed.
 *
 * <p>Persistence here MUST run in a brand-new transaction (PROPAGATION_REQUIRES_NEW),
 * not the default @Transactional REQUIRED that JpaRepository.save() uses on its own:
 * at AFTER_COMMIT time the producer's transaction has physically committed but Spring's
 * TransactionSynchronizationManager hasn't unbound its resources yet, so a plain
 * REQUIRED call silently "joins" that already-committed transaction instead of opening
 * a new one -- the save() call runs, throws nothing, but is never actually committed
 * anywhere (discovered by an integration test finding 0 rows with no error at all).
 * A TransactionTemplate is used (rather than @Transactional on a private method) because
 * this class calls recordFailure() via self-invocation, which bypasses Spring's AOP proxy
 * and would silently make a @Transactional annotation on it a no-op.
 */
@Component
public class DomainEventAuditListener {

    private static final Logger log = LoggerFactory.getLogger(DomainEventAuditListener.class);

    private final AuditLogRepository auditLogRepository;
    private final FailedEventRepository failedEventRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate requiresNewTransactionTemplate;

    public DomainEventAuditListener(AuditLogRepository auditLogRepository,
                                     FailedEventRepository failedEventRepository,
                                     ObjectMapper objectMapper,
                                     PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.failedEventRepository = failedEventRepository;
        this.objectMapper = objectMapper;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                String payloadJson = writePayloadJson(envelope);
                auditLogRepository.save(new AuditLogEntry(
                    UUID.randomUUID(), envelope.tenantId(), envelope.eventId(), envelope.eventType(),
                    envelope.schemaVersion(), envelope.sequenceNumber(), envelope.occurredAt(), Instant.now(), payloadJson));
            });
        } catch (Exception e) {
            log.error("Failed to persist audit_log entry for event {}", envelope.eventType(), e);
            recordFailure(envelope, e);
        }
    }

    private void recordFailure(DomainEventEnvelope<?> envelope, Exception cause) {
        try {
            requiresNewTransactionTemplate.executeWithoutResult(status -> {
                String payloadJson = writePayloadJson(envelope);
                failedEventRepository.save(new FailedEvent(
                    envelope.tenantId(), envelope.eventId(), envelope.eventType(), "audit",
                    cause.getMessage(), Instant.now(), payloadJson));
            });
        } catch (Exception dlqFailure) {
            log.error("Failed to write dead-letter failed_event for event {} -- not recorded anywhere further",
                envelope.eventType(), dlqFailure);
        }
    }

    private String writePayloadJson(DomainEventEnvelope<?> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope.payload());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize payload for event " + envelope.eventType(), e);
        }
    }
}
