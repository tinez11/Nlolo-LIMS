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
import java.util.UUID;

/**
 * The unit-linked charge sweep (spec §6), built like CoveredLifeSweep: selection is SQL across tenants, ids only
 * (unitlinked V2's unit_linked_policies()); each policy runs under its own tenant in its own transaction, so one
 * failure is logged and the next policy is still charged. It depends on this module's own beans only -- a sweep
 * that injects another module's implementation class breaks every test context that mocks that module's api.
 */
@Component
public class ChargeSweep {

    private static final Logger log = LoggerFactory.getLogger(ChargeSweep.class);

    private final NoticeLogRepository selection;
    private final ChargeRun chargeRun;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    ChargeSweep(NoticeLogRepository selection, ChargeRun chargeRun, PlatformTransactionManager transactionManager,
                @Qualifier("unitLinkedClock") Clock clock) {
        this.selection = selection;
        this.chargeRun = chargeRun;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${unitlinked.charge-sweep-interval-ms:3600000}",
        initialDelayString = "${unitlinked.charge-sweep-interval-ms:3600000}")
    public void drain() {
        LocalDate today = BindingRule.civilDate(clock.instant());
        for (Object[] row : selection.unitLinkedPolicies()) {
            sweepOne((String) row[0], (UUID) row[1], today);
        }
    }

    /** One policy, under its tenant, in its own transaction, as of {@code today} (tests sweep on chosen days). */
    public void sweepOne(String policyNumber, UUID tenantId, LocalDate today) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            requiresNew.executeWithoutResult(status -> chargeRun.runDue(policyNumber, today));
        } catch (Exception e) {
            log.error("Unit-linked charge sweep of {} failed and was rolled back; it is retried on the next run", policyNumber, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
