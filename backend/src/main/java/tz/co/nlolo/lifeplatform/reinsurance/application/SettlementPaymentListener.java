package tz.co.nlolo.lifeplatform.reinsurance.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.Map;
import java.util.UUID;

/**
 * A payment to a reinsurer (2026-11-08) made under "Payments to make", or refused by the rail: payment answers under the
 * settlement's own purpose, and only that purpose is read here.
 */
@Component
class SettlementPaymentListener {

    private static final Logger log = LoggerFactory.getLogger(SettlementPaymentListener.class);

    private final StatementSettlements settlements;
    private final TransactionTemplate requiresNew;

    SettlementPaymentListener(StatementSettlements settlements, PlatformTransactionManager transactionManager) {
        this.settlements = settlements;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"payment.DisbursementCompleted".equals(envelope.eventType())
                && !"payment.DisbursementFailed".equals(envelope.eventType())) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> p = (Map<String, Object>) envelope.payload();
        if (!StatementSettlements.PURPOSE.equals(p.get("purpose"))) {
            return;
        }
        boolean completed = "payment.DisbursementCompleted".equals(envelope.eventType());
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            UUID settlementId = UUID.fromString(String.valueOf(p.get("sourceRef")));
            requiresNew.executeWithoutResult(status -> settlements.payoutSettled(settlementId, completed));
        } catch (Exception e) {
            log.error("reinsurance failed to record the outcome of settlement payout {} for tenant {}", p.get("sourceRef"),
                envelope.tenantId(), e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }
}
