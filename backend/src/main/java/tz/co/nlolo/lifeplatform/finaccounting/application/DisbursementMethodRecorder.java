package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which rail paid each disbursement (IFRS 17 I3b, user answer Q3), from {@code payment.DisbursementCompleted}: a
 * mobile money payout leaves through 1140, a bank transfer (EFT) through 1130. The paying module's own paid event --
 * ClaimSettled, SurrenderPaid, PayoutPaid ... -- carries the same source reference and posts after this has run:
 * FIRST among the AFTER_COMMIT listeners on the completion, as the classification listener is on an issue.
 */
@Component("finaccountingDisbursementMethodRecorder")
class DisbursementMethodRecorder {

    private static final Logger log = LoggerFactory.getLogger(DisbursementMethodRecorder.class);
    static final String MOBILE_MONEY = "MOBILE_MONEY";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    DisbursementMethodRecorder(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        if (!"payment.DisbursementCompleted".equals(envelope.eventType())) {
            return;
        }
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> p = (Map<String, Object>) envelope.payload();
            Object method = p.get("method");
            requiresNew.executeWithoutResult(status -> jdbc.update("INSERT INTO finaccounting.disbursement_method"
                    + " (tenant_id, purpose, source_ref, method) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                envelope.tenantId(), String.valueOf(p.get("purpose")), String.valueOf(p.get("sourceRef")),
                method == null ? MOBILE_MONEY : method.toString()));
        } catch (Exception e) {
            // Without it the payout is posted as mobile money: wrong account if it went by bank, never lost.
            log.error("finaccounting could not record the rail of disbursement {}", envelope.payload(), e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    /** The rail that paid {@code sourceRef}; empty when no completion was recorded (it posts as mobile money). */
    Optional<String> methodOf(UUID tenantId, String sourceRef) {
        return jdbc.queryForList("SELECT method FROM finaccounting.disbursement_method WHERE tenant_id = ?"
            + " AND source_ref = ? ORDER BY recorded_at DESC LIMIT 1", String.class, tenantId, sourceRef).stream().findFirst();
    }
}
