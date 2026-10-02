package tz.co.nlolo.lifeplatform.accumulation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Under the envelope's tenant, in its own transaction, never rethrowing -- benefitpayout's shape.
 *
 * <p>One addition: a lost posting race is reported for what it is. ux_posting_source rejecting a
 * concurrent duplicate is the exactly-once guarantee WORKING, so it is logged at INFO as a dropped
 * duplicate rather than at ERROR as a failure somebody would chase.
 */
// Explicit name: benefitpayout declares an EnvelopeRunner too, and two default "envelopeRunner"
// beans fail application startup.
@Component("accumulationEnvelopeRunner")
class EnvelopeRunner {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeRunner.class);

    private final TransactionTemplate requiresNew;

    EnvelopeRunner(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    void run(DomainEventEnvelope<?> envelope, Consumer<Map<String, Object>> handler) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload = (Map<String, Object>) envelope.payload();
            requiresNew.executeWithoutResult(status -> handler.accept(payload));
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ux_posting_source")) {
                log.info("accumulation dropped a concurrent duplicate of {} for tenant {} -- already posted",
                    envelope.eventType(), envelope.tenantId());
            } else {
                log.error("accumulation failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
            }
        } catch (Exception e) {
            log.error("accumulation failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
