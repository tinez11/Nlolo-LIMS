package tz.co.nlolo.lifeplatform.bonus.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Under the envelope's tenant, in its own transaction, never rethrowing -- accumulation's shape. A
 * lost race on a once-only index is the guarantee working, so it is INFO, not ERROR.
 */
@Component("bonusEnvelopeRunner")
class BonusEnvelopeRunner {

    private static final Logger log = LoggerFactory.getLogger(BonusEnvelopeRunner.class);

    private final TransactionTemplate requiresNew;

    BonusEnvelopeRunner(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    void run(DomainEventEnvelope<?> envelope, Consumer<DomainEventEnvelope<?>> handler) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNew.executeWithoutResult(status -> handler.accept(envelope));
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains("ux_status_event_event") || cause.contains("ux_attachment_source") || cause.contains("ux_settlement_exit")) {
                log.info("bonus dropped a concurrent duplicate of {} for tenant {}", envelope.eventType(), envelope.tenantId());
            } else {
                log.error("bonus failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
            }
        } catch (Exception e) {
            log.error("bonus failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
