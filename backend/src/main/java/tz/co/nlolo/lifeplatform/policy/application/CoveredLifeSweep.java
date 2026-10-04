package tz.co.nlolo.lifeplatform.policy.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.policy.infrastructure.CoveredLifeRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

/**
 * Family funeral cover's nightly pass: scheduled ends take effect, children age out, and every life is
 * re-priced at the policy's anniversary. Built like the annuity VestingSweep: selection is SQL across
 * tenants, ids only (policy V35); each policy is swept under its own tenant in its own transaction, so one
 * failure is logged and the next policy is still swept. The initial delay keeps it out of short test
 * contexts; application-local.yml runs it every minute.
 */
@Component
public class CoveredLifeSweep {

    private static final Logger log = LoggerFactory.getLogger(CoveredLifeSweep.class);
    private static final ZoneId CIVIL_ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    private final CoveredLifeRepository lives;
    private final PolicyApiImpl policyApi;
    private final TransactionTemplate requiresNew;

    public CoveredLifeSweep(CoveredLifeRepository lives, PolicyApiImpl policyApi, PlatformTransactionManager transactionManager) {
        this.lives = lives;
        this.policyApi = policyApi;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelayString = "${funeral.covered-life-sweep-interval-ms:86400000}",
        initialDelayString = "${funeral.covered-life-sweep-interval-ms:86400000}")
    public void drain() {
        LocalDate today = LocalDate.now(CIVIL_ZONE);
        for (Object[] row : lives.policiesWithActiveLives()) {
            sweepOne((String) row[0], (UUID) row[1], today);
        }
    }

    /** One policy, under its tenant, in its own transaction, as of {@code today} (tests sweep on chosen days). */
    public void sweepOne(String policyNumber, UUID tenantId, LocalDate today) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            requiresNew.executeWithoutResult(status -> policyApi.sweepFuneralPolicy(policyNumber, today));
        } catch (Exception e) {
            log.error("Covered-life sweep of {} failed and was rolled back; it is retried tomorrow", policyNumber, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
