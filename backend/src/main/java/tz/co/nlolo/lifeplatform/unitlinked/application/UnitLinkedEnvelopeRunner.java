package tz.co.nlolo.lifeplatform.unitlinked.application;

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
 * Under the envelope's tenant, in its own transaction, never rethrowing -- accumulation's EnvelopeRunner, for its
 * reasons. A lost race on ux_unit_entry_source or the pending-order source key is the exactly-once guarantee
 * working, so it is logged at INFO as a dropped duplicate rather than at ERROR.
 */
@Component("unitLinkedEnvelopeRunner")
class UnitLinkedEnvelopeRunner {

    private static final Logger log = LoggerFactory.getLogger(UnitLinkedEnvelopeRunner.class);

    private final TransactionTemplate requiresNew;

    UnitLinkedEnvelopeRunner(PlatformTransactionManager transactionManager) {
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
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains("ux_unit_entry_source") || cause.contains("pending_order_tenant_id_source_type_source_ref_fund_id_key")) {
                log.info("unitlinked dropped a concurrent duplicate of {} for tenant {} -- already recorded",
                    envelope.eventType(), envelope.tenantId());
            } else {
                log.error("unitlinked failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
            }
        } catch (Exception e) {
            log.error("unitlinked failed to process {} for tenant {}", envelope.eventType(), envelope.tenantId(), e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
