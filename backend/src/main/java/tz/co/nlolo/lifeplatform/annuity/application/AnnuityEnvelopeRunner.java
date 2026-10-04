package tz.co.nlolo.lifeplatform.annuity.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Under the envelope's tenant, in its own transaction, never rethrowing -- bonus's shape. An
 * AFTER_COMMIT listener that threw would lose the work silently (step 2's lesson), so every failure is
 * logged with the policy's event, and the work that must be visible records its own failure.
 */
@Component("annuityEnvelopeRunner")
class AnnuityEnvelopeRunner {

    private static final Logger log = LoggerFactory.getLogger(AnnuityEnvelopeRunner.class);

    private final TransactionTemplate requiresNew;

    AnnuityEnvelopeRunner(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    void run(DomainEventEnvelope<?> envelope, Consumer<DomainEventEnvelope<?>> handler) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNew.executeWithoutResult(status -> handler.accept(envelope));
        } catch (Exception e) {
            log.error("annuity failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
