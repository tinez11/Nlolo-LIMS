package tz.co.nlolo.lifeplatform.finaccounting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.DomainEventEnvelope;
import tz.co.nlolo.lifeplatform.TenantContext;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Which reinsurance-held group (IFRS 17 para 61, {@code RI-<treaty>-<year>}) each reinsurance posting belongs to
 * (IFRS 17 I5a, finaccounting V15 section A). Posted lines are immutable, so the group is kept against the posting's
 * source record -- a bordereau, a recovery, a statement -- as reinsurance's events name it, and read back when the
 * extract and the reconciliation total a reinsurance group.
 */
@Component
public class EngineReinsuranceGroups {

    private static final Logger log = LoggerFactory.getLogger(EngineReinsuranceGroups.class);

    /** The event, the payload field naming its source record, and that record's reference type on the posting. */
    private static final Map<String, String[]> SOURCES = Map.of(
        "reinsurance.BordereauPosted", new String[] {"bordereauId", "BORDEREAU"},
        "reinsurance.RecoveryCalculated", new String[] {"recoveryId", "RECOVERY"},
        "reinsurance.StatementApproved", new String[] {"statementId", "STATEMENT"});

    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    EngineReinsuranceGroups(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDomainEvent(DomainEventEnvelope<?> envelope) {
        String[] source = SOURCES.get(envelope.eventType());
        if (source == null || !(envelope.payload() instanceof Map<?, ?> payload)) {
            return;
        }
        Object group = payload.get("reinsuranceGroup");
        Object reference = payload.get(source[0]);
        if (group == null || reference == null) {
            log.warn("{} carries no reinsuranceGroup; its postings are in no reinsurance group", envelope.eventType());
            return;
        }
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(envelope.tenantId());
        try {
            requiresNew.executeWithoutResult(status -> record(envelope.tenantId(), source[1], reference.toString(),
                group.toString()));
        } catch (RuntimeException e) {
            log.error("Could not map {} {} to its reinsurance group", source[1], reference, e);
        } finally {
            if (previous != null) TenantContext.set(previous); else TenantContext.clear();
        }
    }

    void record(UUID tenantId, String referenceType, String reference, String groupKey) {
        jdbc.update("INSERT INTO finaccounting.reinsurance_group_ref (tenant_id, reference_type, reference, group_key)"
            + " VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING", tenantId, referenceType, reference, groupKey);
    }

    Optional<String> groupOf(UUID tenantId, String referenceType, String reference) {
        List<String> found = jdbc.queryForList("SELECT group_key FROM finaccounting.reinsurance_group_ref"
            + " WHERE tenant_id = ? AND reference_type = ? AND reference = ?", String.class, tenantId, referenceType, reference);
        return found.stream().findFirst();
    }
}
