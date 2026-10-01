package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.PayoutInstalmentRepository;

import java.util.UUID;

/**
 * Brings scheduled instalments due as their dates arrive, across every tenant, each under its own.
 *
 * <p><b>Why a Spring {@code @Scheduled} rather than pg_cron</b>, exactly as {@code CoverExpiryDrain}
 * argues: the transition has consumers. Falling due can mature a policy, and only a published event
 * reaches billing and audit. So the SELECTION is SQL ({@code instalments_falling_due()}) and the
 * TRANSITION is Java.
 *
 * <p>Exactly-once comes from the aggregate rather than from running in one place: {@code fallDue}
 * re-reads the instalment, its {@code @Version} makes the winning write exclusive, and the guard on
 * SCHEDULED makes the loser a no-op. Two instances draining the same list is therefore safe.
 *
 * <p>It is deliberately NOT in {@code ScheduledJobsHealthIndicator}: that reads
 * {@code ops.platform_readiness()}, which lists the pg_cron jobs and pg_partman registrations
 * {@code configure-db.sh} installs and a migration cannot. A bean that runs wherever the
 * application runs is not one of those.
 */
@Component
public class PayoutDueDrain {

    private static final Logger log = LoggerFactory.getLogger(PayoutDueDrain.class);

    private final PayoutInstalmentRepository instalments;
    private final BenefitPayoutApiImpl api;

    public PayoutDueDrain(PayoutInstalmentRepository instalments, BenefitPayoutApiImpl api) {
        this.instalments = instalments;
        this.api = api;
    }

    /**
     * Hourly by default, first run one interval after startup. Due dates are days, so hourly only
     * ever brings a payout due a few hours earlier than a daily run would, while surviving a missed
     * run cheaply. The initial delay keeps it from firing inside a short-lived test context, where
     * the selector may not be installed.
     */
    @Scheduled(fixedDelayString = "${benefitpayout.due-drain-interval-ms:3600000}",
        initialDelayString = "${benefitpayout.due-drain-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : instalments.findFallingDueAcrossTenants()) {
            fallDueOne((UUID) row[0], (UUID) row[1]);
        }
    }

    /**
     * One instalment under its own tenant. The tenant is set FIRST and around everything, so the
     * read and the write run under RLS against exactly this tenant's rows even though the id
     * arrived from a cross-tenant lookup. One bad row must not cost every other its payout.
     */
    void fallDueOne(UUID instalmentId, UUID tenantId) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            api.fallDue(instalmentId);
        } catch (Exception e) {
            log.error("Failed to bring payout instalment {} due in tenant {}", instalmentId, tenantId, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
