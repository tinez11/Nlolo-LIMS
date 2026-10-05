package tz.co.nlolo.lifeplatform.unitlinked.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.api.PolicyApi;
import tz.co.nlolo.lifeplatform.policy.api.PolicyStatus;
import tz.co.nlolo.lifeplatform.policy.api.PolicyView;
import tz.co.nlolo.lifeplatform.unitlinked.domain.BindingRule;
import tz.co.nlolo.lifeplatform.unitlinked.infrastructure.NoticeLogRepository;

import java.time.Clock;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Unit-linked maturities (plan R4): policy's expiry skips a unit-linked policy, so its units mature here -- on the
 * maturity date the units are sold at that date's own price and the proceeds paid; the policy closes MATURED once
 * payment confirms. A version with no term never matures. Built like ChargeSweep; this module's beans and PolicyApi
 * (an interface) only.
 */
@Component
public class MaturitySweep {

    private static final Logger log = LoggerFactory.getLogger(MaturitySweep.class);

    private final NoticeLogRepository selection;
    private final PolicyApi policyApi;
    private final UnitLedger ledger;
    private final Exits exits;
    private final TransactionTemplate requiresNew;
    private final Clock clock;

    MaturitySweep(NoticeLogRepository selection, PolicyApi policyApi, UnitLedger ledger, Exits exits,
                  PlatformTransactionManager transactionManager, @Qualifier("unitLinkedClock") Clock clock) {
        this.selection = selection;
        this.policyApi = policyApi;
        this.ledger = ledger;
        this.exits = exits;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${unitlinked.maturity-sweep-interval-ms:3600000}",
        initialDelayString = "${unitlinked.maturity-sweep-interval-ms:3600000}")
    public void drain() {
        LocalDate today = BindingRule.civilDate(clock.instant());
        for (Object[] row : selection.unitLinkedPolicies()) {
            sweepOne((String) row[0], (UUID) row[1], today);
        }
    }

    public void sweepOne(String policyNumber, UUID tenantId, LocalDate today) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            requiresNew.executeWithoutResult(status -> {
                PolicyView policy = policyApi.getPolicy(policyNumber);
                if (policy.maturityDate() == null || policy.maturityDate().isAfter(today)) {
                    return;
                }
                if (policy.status() != PolicyStatus.ACTIVE && policy.status() != PolicyStatus.REINSTATED) {
                    return;
                }
                if (ledger.isFrozen(policyNumber)) {
                    return;
                }
                exits.mature(policyNumber, policy.maturityDate());
            });
        } catch (Exception e) {
            log.error("Unit-linked maturity sweep of {} failed and was rolled back; it is retried on the next run", policyNumber, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
