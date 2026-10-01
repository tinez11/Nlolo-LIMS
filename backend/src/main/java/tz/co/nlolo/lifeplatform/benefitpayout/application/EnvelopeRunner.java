package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * How this module reacts to another module's event: under the envelope's own tenant, in its own
 * transaction, and never letting a failure escape.
 *
 * <p>The isolation is the point. These handlers run AFTER_COMMIT on somebody else's write -- a
 * policy being issued, a claim being approved -- and a payout schedule that cannot be written must
 * not undo the business event that caused it. The failure is logged loudly and the originating
 * transaction stands.
 *
 * <p>One component rather than a copy per listener, so a fix here cannot reach one and miss
 * another.
 */
@Component
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
        } catch (Exception e) {
            log.error("benefitpayout failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
