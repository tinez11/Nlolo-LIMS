package tz.co.nlolo.lifeplatform.benefitpayout.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.PaymentRunRepository;
import tz.co.nlolo.lifeplatform.benefitpayout.infrastructure.PayoutStreamRepository;

import java.time.LocalDate;
import java.util.UUID;

/**
 * The income side of the day: suspend the streams whose proof of life has lapsed, then assemble
 * each tenant's batch from what is left (decision Q7).
 *
 * <p>Order matters and is the point. Suspending first means a stream that went overdue overnight
 * cannot slip into today's run, so the batch one person releases contains only lives somebody has
 * confirmed within the product's interval.
 *
 * <p>Both halves read ids through SECURITY DEFINER selectors, because a sweep has no tenant of its
 * own and RLS would otherwise show it nothing. Each row is then handled under its own tenant, and
 * a failure on one tenant is logged rather than left to abandon every tenant after it.
 */
@Component
public class PaymentRunDrain {

    private static final Logger log = LoggerFactory.getLogger(PaymentRunDrain.class);

    private final PaymentRunRepository runs;
    private final PayoutStreamRepository streams;
    private final BenefitPayoutApiImpl api;

    public PaymentRunDrain(PaymentRunRepository runs, PayoutStreamRepository streams, BenefitPayoutApiImpl api) {
        this.runs = runs;
        this.streams = streams;
        this.api = api;
    }

    @Scheduled(fixedDelayString = "${benefitpayout.run-drain-interval-ms:3600000}",
        initialDelayString = "${benefitpayout.run-drain-interval-ms:3600000}")
    public void drain() {
        for (Object[] row : streams.findDueForProofOfLifeAcrossTenants()) {
            inTenant((UUID) row[1], () -> api.suspendStream((UUID) row[0]));
        }
        for (UUID tenantId : runs.findTenantsWithStreamInstalmentsDue()) {
            inTenant(tenantId, () -> api.prepareRun(LocalDate.now()));
        }
    }

    private void inTenant(UUID tenantId, Runnable work) {
        UUID previous = TenantContext.getOrNull();
        TenantContext.set(tenantId);
        try {
            work.run();
        } catch (Exception e) {
            log.error("Payment-run drain failed for tenant {}", tenantId, e);
        } finally {
            if (previous != null) {
                TenantContext.set(previous);
            } else {
                TenantContext.clear();
            }
        }
    }
}
