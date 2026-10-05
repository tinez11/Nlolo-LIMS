package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.NoticeLogRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Month;
import java.util.UUID;

/**
 * Last calendar year's unit statement for every unit-linked policy, once (U2, spec §6), run through January -- a
 * policy missed on one day (an outage, a failure) is caught on the next. ChargeSweep's shape: selection is SQL across
 * tenants; each policy under its own tenant in its own transaction, so one failure is logged and the next still files.
 */
@Component
public class StatementDrain {

    private static final Logger log = LoggerFactory.getLogger(StatementDrain.class);

    private final NoticeLogRepository selection;
    private final Statements statements;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    StatementDrain(NoticeLogRepository selection, Statements statements, PlatformTransactionManager transactionManager,
                   @Qualifier("unitLinkedClock") Clock clock) {
        this.selection = selection;
        this.statements = statements;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${unitlinked.statement-interval-ms:86400000}",
        initialDelayString = "${unitlinked.statement-interval-ms:86400000}")
    public void drain() {
        LocalDate today = BindingRule.civilDate(clock.instant());
        if (today.getMonth() != Month.JANUARY) {
            return;
        }
        for (Object[] row : selection.unitLinkedPolicies()) {
            sweepOne((String) row[0], (UUID) row[1], today);
        }
    }

    /** One policy, under its tenant, in its own transaction, as of {@code today} (tests choose the day). */
    public void sweepOne(String policyNumber, UUID tenantId, LocalDate today) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            requiresNew.executeWithoutResult(status -> statements.fileAnnual(policyNumber, today));
        } catch (Exception e) {
            log.error("Annual unit statement of {} failed and was rolled back; it is retried on the next run", policyNumber, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
